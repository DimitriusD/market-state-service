package com.trading.mss.api;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link AsyncSnapshotPort} backed by an {@link Executor}, so the blocking Binance HTTP call never
 * runs on the Kafka consumer thread. Owns the retry/backoff loop (safe to sleep here) and delegates
 * the actual call to the resilient {@link BinanceSpotSnapshotApiService} (circuit breaker + rate
 * limiter + Retry-After gate).
 *
 * <p>Touches no {@code SymbolState}: it only fetches and returns an immutable snapshot, leaving all
 * state mutation to the consumer thread that drains the future.
 */
@Slf4j
public class ExecutorSnapshotFetcher implements AsyncSnapshotPort {

    private final BinanceSpotSnapshotApiService snapshotApi;
    private final Executor executor;
    private final int maxRetries;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public ExecutorSnapshotFetcher(BinanceSpotSnapshotApiService snapshotApi,
                                   Executor executor,
                                   int maxRetries,
                                   long baseBackoffMs,
                                   long maxBackoffMs) {
        this.snapshotApi = snapshotApi;
        this.executor = executor;
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @Override
    public CompletableFuture<OrderBookSnapshot> fetch(String symbol, int depthLimit) {
        return CompletableFuture.supplyAsync(() -> loadWithBackoff(symbol, depthLimit), executor);
    }

    private OrderBookSnapshot loadWithBackoff(String symbol, int depthLimit) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return snapshotApi.load(symbol, depthLimit);
            } catch (BinanceRateLimitedException | CallNotPermittedException e) {
                // Rate limited / circuit open: retrying now would only hammer Binance (or fast-fail).
                // Give up immediately and let the per-symbol bootstrap cooldown schedule the next try.
                log.warn("Snapshot fetch short-circuited: symbol={} attempt={}/{} reason={}",
                        symbol, attempt, maxRetries, e.getClass().getSimpleName());
                throw e;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Snapshot fetch failed: symbol={} attempt={}/{} error={}",
                        symbol, attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    sleepBackoff(attempt);
                }
            }
        }
        throw lastError;
    }

    private void sleepBackoff(int attempt) {
        long exponential = Math.min(maxBackoffMs, baseBackoffMs * (1L << (attempt - 1)));
        long jitter = exponential > 0 ? ThreadLocalRandom.current().nextLong(exponential / 2 + 1) : 0;
        try {
            Thread.sleep(exponential + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during snapshot fetch backoff", e);
        }
    }
}
