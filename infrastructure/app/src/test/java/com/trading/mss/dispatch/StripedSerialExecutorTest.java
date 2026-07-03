package com.trading.mss.dispatch;

import com.trading.mss.domain.model.SymbolKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StripedSerialExecutorTest {

    private static final SymbolKey BTC = new SymbolKey("binance", "spot", "BTCUSDT");
    private static final SymbolKey ETH = new SymbolKey("binance", "spot", "ETHUSDT");

    private StripedSerialExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null && executor.isRunning()) {
            executor.stop();
        }
    }

    private StripedSerialExecutor started(int stripes, int capacity) {
        executor = new StripedSerialExecutor(stripes, capacity);
        executor.start();
        return executor;
    }

    @Test
    void commandsForOneKeyRunInFifoOrderOnOneThread() throws Exception {
        started(4, 100);
        int commands = 500;
        List<Integer> order = new ArrayList<>();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(commands);

        Executor btc = executor.executorFor(BTC);
        for (int i = 0; i < commands; i++) {
            int seq = i;
            btc.execute(() -> {
                order.add(seq);   // safe: single worker thread per key
                threads.add(Thread.currentThread().getName());
                done.countDown();
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, threads.size(), "one key must be pinned to exactly one worker thread");
        for (int i = 0; i < commands; i++) {
            assertEquals(i, order.get(i), "FIFO order violated at " + i);
        }
    }

    @Test
    void differentKeysMayRunConcurrentlyOnDifferentStripes() throws Exception {
        int stripes = 16;
        started(stripes, 100);
        SymbolKey other = keyOnDifferentStripe(BTC, stripes);
        CountDownLatch btcEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ethRan = new CountDownLatch(1);

        executor.executorFor(BTC).execute(() -> {
            btcEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(btcEntered.await(2, TimeUnit.SECONDS));

        executor.executorFor(other).execute(ethRan::countDown);
        boolean independent = ethRan.await(2, TimeUnit.SECONDS);
        release.countDown();

        assertTrue(independent, "a command on another stripe must not be blocked behind a busy BTC stripe");
    }

    /** Mirrors StripedSerialExecutor's stripe selection to pick a key off BTC's stripe. */
    private static SymbolKey keyOnDifferentStripe(SymbolKey reference, int stripes) {
        int refStripe = Math.floorMod(reference.canonical().hashCode(), stripes);
        for (int i = 0; i < 10_000; i++) {
            SymbolKey candidate = new SymbolKey("binance", "spot", "SYM" + i + "USDT");
            if (Math.floorMod(candidate.canonical().hashCode(), stripes) != refStripe) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find a key on a different stripe");
    }

    @Test
    void executeBlocksWhenQueueFull_thenProceeds() throws Exception {
        started(1, 1);
        CountDownLatch blockWorker = new CountDownLatch(1);
        CountDownLatch allRan = new CountDownLatch(3);

        Executor ex = executor.executorFor(BTC);
        ex.execute(() -> {
            try {
                blockWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            allRan.countDown();
        });
        ex.execute(allRan::countDown); // fills the queue (capacity 1)

        AtomicBoolean thirdSubmitted = new AtomicBoolean(false);
        Thread submitter = new Thread(() -> {
            ex.execute(allRan::countDown); // must block until worker unblocks
            thirdSubmitted.set(true);
        });
        submitter.start();

        Thread.sleep(200);
        assertFalse(thirdSubmitted.get(), "third submit should be blocked while the queue is full");

        blockWorker.countDown();
        submitter.join(5000);
        assertTrue(thirdSubmitted.get());
        assertTrue(allRan.await(5, TimeUnit.SECONDS));
    }

    @Test
    void selfSubmissionFromWorkerDoesNotDeadlockEvenWithFullQueue() throws Exception {
        started(1, 1);
        CountDownLatch followUpRan = new CountDownLatch(1);
        CountDownLatch queuedRan = new CountDownLatch(1);

        Executor ex = executor.executorFor(BTC);
        CountDownLatch workerBusy = new CountDownLatch(1);
        ex.execute(() -> {
            try {
                workerBusy.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // Queue is full (one task parked below); a blocking put here would deadlock.
            ex.execute(followUpRan::countDown);
        });
        ex.execute(queuedRan::countDown); // fills the queue while worker is busy
        workerBusy.countDown();

        assertTrue(followUpRan.await(5, TimeUnit.SECONDS), "self-submitted task must run (no deadlock)");
        assertTrue(queuedRan.await(5, TimeUnit.SECONDS));
    }

    @Test
    void selfSubmittedTaskRunsAfterCurrentCommandCompletes_notReentrantly() throws Exception {
        started(1, 10);
        AtomicBoolean outerFinished = new AtomicBoolean(false);
        AtomicBoolean reentrant = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Executor ex = executor.executorFor(BTC);
        ex.execute(() -> {
            ex.execute(() -> {
                reentrant.set(!outerFinished.get());
                done.countDown();
            });
            outerFinished.set(true);
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(reentrant.get(), "follow-up must run strictly after the submitting command returns");
    }

    @Test
    void starvationBudget_interleavesMainQueueWhileLocalChainKeepsGrowing() throws Exception {
        started(1, 10);
        CountDownLatch mainRan = new CountDownLatch(1);
        AtomicInteger chain = new AtomicInteger();
        Executor ex = executor.executorFor(BTC);

        // A self-perpetuating local chain far beyond the budget.
        Runnable[] selfChain = new Runnable[1];
        selfChain[0] = () -> {
            if (chain.incrementAndGet() < 1000) {
                ex.execute(selfChain[0]);
            }
        };
        ex.execute(selfChain[0]);
        // Give the chain a moment to enter local-drain mode, then enqueue a main-queue task.
        Thread.sleep(50);
        ex.execute(mainRan::countDown);

        assertTrue(mainRan.await(5, TimeUnit.SECONDS),
                "main-queue command must run despite an ongoing local-task chain");
    }

    @Test
    void tryExecuteReturnsFalseWhenQueueFull_andTrueOtherwise() throws Exception {
        started(1, 1);
        CountDownLatch blockWorker = new CountDownLatch(1);
        executor.executorFor(BTC).execute(() -> {
            try {
                blockWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(executor.tryExecute(BTC, () -> {})); // fills queue
        assertFalse(executor.tryExecute(BTC, () -> {}), "full queue must reject, not block");
        blockWorker.countDown();
    }

    @Test
    void stopDrainsAcceptedCommands() throws Exception {
        started(2, 100);
        int commands = 200;
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < commands; i++) {
            executor.executorFor(i % 2 == 0 ? BTC : ETH).execute(ran::incrementAndGet);
        }

        executor.stop();

        assertEquals(commands, ran.get(), "every accepted command must run before stop() returns");
    }

    @Test
    void submissionsAfterStopAreDroppedWithoutThrowing() {
        started(1, 10);
        executor.stop();

        List<String> ran = new CopyOnWriteArrayList<>();
        assertDoesNotThrow(() -> executor.executorFor(BTC).execute(() -> ran.add("x")));
        assertFalse(executor.tryExecute(BTC, () -> ran.add("y")));
        assertTrue(ran.isEmpty());
        assertTrue(executor.droppedAfterCloseCount() >= 1);
    }

    @Test
    void commandExceptionDoesNotKillTheStripe() throws Exception {
        started(1, 10);
        CountDownLatch survived = new CountDownLatch(1);
        Executor ex = executor.executorFor(BTC);

        ex.execute(() -> {
            throw new IllegalStateException("boom");
        });
        ex.execute(survived::countDown);

        assertTrue(survived.await(5, TimeUnit.SECONDS), "stripe must survive a throwing command");
    }
}
