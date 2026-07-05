package com.trading.mss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, single-source binding for the {@code app.*} configuration tree (see application.yml).
 *
 * <p>Defaults live once, in application.yml, expressed as {@code ${ENV:default}} so ops can override
 * every knob via env var. This record only binds — it carries no literal defaults, which is what keeps
 * the wiring in {@link InfrastructureConfig} free of the duplicated {@code @Value("...:default")}
 * literals that previously drifted between beans.
 *
 * <p>{@code app.kafka.*} is intentionally not bound here: those properties are consumed only by the
 * kafka-adapter module and stay local to it.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Binance binance,
        State state,
        Bootstrap bootstrap,
        MarketState marketState) {

    public record Binance(Rest rest, Snapshot snapshot) {
        public record Rest(
                String baseUrl,
                long connectTimeoutMs,
                long readTimeoutMs,
                Resilience resilience) {
            public record Resilience(
                    int rateLimitPerMinute,
                    long rateLimitTimeoutMs,
                    float circuitFailureRateThreshold,
                    int circuitSlidingWindowSize,
                    int circuitMinimumCalls,
                    long circuitWaitDurationMs,
                    int circuitPermittedCallsHalfOpen) {
            }
        }

        public record Snapshot(int depthLimit) {
        }
    }

    public record State(
            int maxBufferedEvents,
            long bootstrapCooldownMs,
            long snapshotTimeoutMs,
            Staleness staleness,
            Watchdog watchdog,
            Dispatcher dispatcher) {
        public record Staleness(long softThresholdMs, long hardThresholdMs) {
        }

        public record Watchdog(long intervalMs) {
        }

        public record Dispatcher(int stripes, int queueCapacity) {
        }
    }

    public record Bootstrap(
            int snapshotFetchPoolSize,
            int snapshotFetchMaxRetries,
            long snapshotFetchBaseBackoffMs,
            long snapshotFetchMaxBackoffMs) {
    }

    public record MarketState(Publish publish) {
        public record Publish(int topnDepth) {
        }
    }

    /**
     * Worst-case wall-clock of a single snapshot fetch: every retry pays a connect + read timeout plus
     * backoff (~1.5x the cap on average across the exponential schedule). The {@code SNAPSHOT_LOADING}
     * watchdog timeout ({@link State#snapshotTimeoutMs()}) must exceed this, else it double-fires against
     * a slow-but-alive fetch.
     */
    public long snapshotFetchWorstCaseMs() {
        return (long) (bootstrap.snapshotFetchMaxRetries()
                * (binance.rest().connectTimeoutMs()
                        + binance.rest().readTimeoutMs()
                        + 1.5 * bootstrap.snapshotFetchMaxBackoffMs()));
    }

    public boolean snapshotTimeoutBelowFetchWorstCase() {
        return state.snapshotTimeoutMs() <= snapshotFetchWorstCaseMs();
    }
}
