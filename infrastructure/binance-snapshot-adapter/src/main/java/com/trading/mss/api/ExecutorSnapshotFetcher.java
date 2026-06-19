package com.trading.mss.api;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

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
