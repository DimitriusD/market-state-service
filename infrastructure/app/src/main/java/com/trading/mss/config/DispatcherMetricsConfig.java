package com.trading.mss.config;

import com.trading.mss.dispatch.StripedSerialExecutor;
import com.trading.mss.watchdog.SymbolStateWatchdog;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Operational visibility for the symbol-command dispatcher. The two signals that matter most:
 * sustained {@code mss.dispatcher.enqueue.blocked.time} growth (backpressure on the Kafka listener,
 * precursor of max.poll.interval trouble) and any {@code mss.dispatcher.dropped.after.close}
 * outside of shutdown.
 */
@Configuration
public class DispatcherMetricsConfig {

    @Bean
    public MeterBinder dispatcherMetrics(StripedSerialExecutor symbolExecutor, SymbolStateWatchdog symbolStateWatchdog) {
        return registry -> {
            for (int i = 0; i < symbolExecutor.stripeCount(); i++) {
                final int stripe = i;
                Gauge.builder("mss.dispatcher.queue.depth", () -> symbolExecutor.queueDepth(stripe))
                        .tag("stripe", String.valueOf(stripe))
                        .description("Commands waiting in the stripe's main queue")
                        .register(registry);
                Gauge.builder("mss.dispatcher.local.tasks.depth", () -> symbolExecutor.localTasksDepth(stripe))
                        .tag("stripe", String.valueOf(stripe))
                        .description("Self-submitted follow-up commands pending on the stripe worker")
                        .register(registry);
            }
            FunctionCounter.builder("mss.dispatcher.enqueue.blocked", symbolExecutor,
                            StripedSerialExecutor::enqueueBlockedCount)
                    .description("Blocking enqueues (queue was full when a command was submitted)")
                    .register(registry);
            FunctionCounter.builder("mss.dispatcher.enqueue.blocked.time", symbolExecutor,
                            StripedSerialExecutor::enqueueBlockedTotalMs)
                    .baseUnit("milliseconds")
                    .description("Total time submitters spent blocked on full stripe queues")
                    .register(registry);
            FunctionCounter.builder("mss.dispatcher.dropped.after.close", symbolExecutor,
                            StripedSerialExecutor::droppedAfterCloseCount)
                    .description("Commands dropped because the dispatcher was already stopped")
                    .register(registry);
            FunctionCounter.builder("mss.watchdog.ticks.dropped", symbolStateWatchdog,
                            SymbolStateWatchdog::droppedTickCount)
                    .description("Watchdog ticks rejected by a full queue or stopped dispatcher")
                    .register(registry);
        };
    }
}
