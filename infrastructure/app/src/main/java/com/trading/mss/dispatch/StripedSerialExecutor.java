package com.trading.mss.dispatch;

import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.port.output.SymbolExecutorPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialized per-symbol command execution over N stripes.
 *
 * <p>Each stripe owns one worker thread draining a bounded queue; a symbol is pinned to a stripe by
 * the hash of its {@link InstrumentKey#canonical()} form, so all commands of one symbol run strictly
 * sequentially. {@code execute} blocks when the stripe queue is full (backpressure on the Kafka
 * listener); {@code tryExecute} drops instead (watchdog ticks are idempotent).
 *
 * <p>Self-submission: a command running on a stripe worker may submit a follow-up command to its
 * own stripe (e.g. a snapshot future already completed at callback-attach time runs the dispatch on
 * the worker itself). Blocking on the own full queue would deadlock the stripe, so such tasks go to
 * a thread-confined {@code localTasks} deque, drained before the main queue with a budget
 * ({@value #LOCAL_TASKS_BUDGET} per round) to guard against starving the main queue.
 *
 * <p>Lifecycle: {@link SmartLifecycle} with default phase 0 — Kafka listener containers (phase
 * {@code Integer.MAX_VALUE - 100}) stop first, then {@link #stop()} drains the queues and joins the
 * workers, so no accepted command is lost. Submissions after stop are logged and dropped.
 */
@Slf4j
public class StripedSerialExecutor implements SymbolExecutorPort, SmartLifecycle {

    private static final int LOCAL_TASKS_BUDGET = 100;
    private static final long IDLE_POLL_MS = 100;
    private static final long STOP_JOIN_TIMEOUT_MS = 10_000;

    private final Stripe[] stripes;
    private final AtomicLong droppedAfterClose = new AtomicLong();
    private final AtomicLong enqueueBlockedCount = new AtomicLong();
    private final AtomicLong enqueueBlockedTotalMs = new AtomicLong();

    private volatile boolean accepting = false;

    public StripedSerialExecutor(int stripeCount, int queueCapacity) {
        if (stripeCount < 1) {
            throw new IllegalArgumentException("stripeCount must be >= 1, got " + stripeCount);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, got " + queueCapacity);
        }
        this.stripes = new Stripe[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Stripe(i, queueCapacity);
        }
    }

    @Override
    public Executor executorFor(InstrumentKey key) {
        Stripe stripe = stripeFor(key);
        return task -> submitBlocking(stripe, key, task);
    }

    @Override
    public boolean tryExecute(InstrumentKey key, Runnable task) {
        Stripe stripe = stripeFor(key);
        if (!accepting) {
            dropAfterClose(key);
            return false;
        }
        if (Thread.currentThread() == stripe.worker) {
            stripe.localTasks.addLast(task);
            return true;
        }
        return stripe.queue.offer(task);
    }

    private void submitBlocking(Stripe stripe, InstrumentKey key, Runnable task) {
        if (!accepting) {
            dropAfterClose(key);
            return;
        }
        if (Thread.currentThread() == stripe.worker) {
            // Self-submission from the stripe's own worker: blocking on the own full queue would
            // deadlock the stripe, so route through the thread-confined local deque instead.
            stripe.localTasks.addLast(task);
            return;
        }
        if (stripe.queue.offer(task)) {
            return;
        }
        // Queue full: block the caller (backpressure) and record how long — sustained block time
        // here is the early-warning signal before max.poll.interval.ms trouble.
        long blockedSince = System.nanoTime();
        try {
            stripe.queue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Interrupted while enqueuing command for " + key.canonical(), e);
        } finally {
            enqueueBlockedCount.incrementAndGet();
            enqueueBlockedTotalMs.addAndGet((System.nanoTime() - blockedSince) / 1_000_000);
        }
    }

    private void dropAfterClose(InstrumentKey key) {
        droppedAfterClose.incrementAndGet();
        log.warn("Dropping symbol command: dispatcher closed. key={} droppedAfterClose={}",
                key.canonical(), droppedAfterClose.get());
    }

    private Stripe stripeFor(InstrumentKey key) {
        return stripes[Math.floorMod(key.canonical().hashCode(), stripes.length)];
    }

    // --- SmartLifecycle -------------------------------------------------------------------------

    @Override
    public void start() {
        accepting = true;
        for (Stripe stripe : stripes) {
            stripe.worker.start();
        }
        log.info("StripedSerialExecutor started: stripes={} queueCapacity={}",
                stripes.length, stripes[0].queue.remainingCapacity() + stripes[0].queue.size());
    }

    @Override
    public void stop() {
        accepting = false;
        for (Stripe stripe : stripes) {
            try {
                stripe.worker.join(STOP_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (stripe.worker.isAlive()) {
                log.warn("Stripe worker did not drain within {}ms, interrupting: {} queueDepth={}",
                        STOP_JOIN_TIMEOUT_MS, stripe.worker.getName(), stripe.queue.size());
                stripe.worker.interrupt();
            }
        }
        log.info("StripedSerialExecutor stopped: droppedAfterClose={}", droppedAfterClose.get());
    }

    @Override
    public boolean isRunning() {
        return accepting;
    }

    /**
     * Must be LOWER than the Kafka listener containers' phase ({@code Integer.MAX_VALUE - 100}):
     * stop runs in descending phase order, so containers stop first and the dispatcher drains what
     * they enqueued. The {@code SmartLifecycle} default is {@code Integer.MAX_VALUE} — do not rely on it.
     */
    @Override
    public int getPhase() {
        return 0;
    }

    // --- Observability hooks (used by metrics wiring) --------------------------------------------

    public int stripeCount() {
        return stripes.length;
    }

    public int queueDepth(int stripeIndex) {
        return stripes[stripeIndex].queue.size();
    }

    public long droppedAfterCloseCount() {
        return droppedAfterClose.get();
    }

    /** Approximate (thread-confined deque read from outside) — for gauges only. */
    public int localTasksDepth(int stripeIndex) {
        return stripes[stripeIndex].localTasks.size();
    }

    public long enqueueBlockedCount() {
        return enqueueBlockedCount.get();
    }

    public long enqueueBlockedTotalMs() {
        return enqueueBlockedTotalMs.get();
    }

    // --- Stripe ----------------------------------------------------------------------------------

    private final class Stripe {

        private final BlockingQueue<Runnable> queue;
        /** Thread-confined to {@link #worker}: written and read only by the worker itself. */
        private final ArrayDeque<Runnable> localTasks = new ArrayDeque<>();
        private final Thread worker;

        private Stripe(int index, int queueCapacity) {
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.worker = new Thread(this::runLoop, "state-dispatcher-" + index);
            this.worker.setDaemon(true);
        }

        private void runLoop() {
            while (accepting || !queue.isEmpty() || !localTasks.isEmpty()) {
                try {
                    drainLocalTasksWithBudget();
                    Runnable task = queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        runSafely(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void drainLocalTasksWithBudget() {
            int executed = 0;
            Runnable local;
            while ((local = localTasks.pollFirst()) != null) {
                runSafely(local);
                if (++executed >= LOCAL_TASKS_BUDGET && !localTasks.isEmpty()) {
                    // Starvation guard: a command chain keeps re-submitting to its own stripe.
                    log.warn("localTasks budget exhausted on {}: executed={} remaining={} — interleaving main queue",
                            worker.getName(), executed, localTasks.size());
                    Runnable main = queue.poll();
                    if (main != null) {
                        runSafely(main);
                    }
                    executed = 0;
                }
            }
        }

        private void runSafely(Runnable task) {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("Symbol command failed on {} — stripe continues", worker.getName(), t);
            }
        }
    }
}
