package com.trading.mss.api;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorSnapshotFetcherTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    void retriesTransientFailures_thenSucceeds() {
        FlakyApi api = new FlakyApi();
        api.failTimes(2, new RuntimeException("blip"));
        api.snapshot = snapshot();
        var fetcher = new ExecutorSnapshotFetcher(api, DIRECT, 3, 0, 0);

        OrderBookSnapshot result = fetcher.fetch("BTCUSDT", 1000).join();

        assertNotNull(result);
        assertEquals(3, api.calls);
    }

    @Test
    void exhaustsRetries_completesExceptionally() {
        FlakyApi api = new FlakyApi();
        api.failTimes(Integer.MAX_VALUE, new RuntimeException("down"));
        var fetcher = new ExecutorSnapshotFetcher(api, DIRECT, 3, 0, 0);

        CompletableFuture<OrderBookSnapshot> future = fetcher.fetch("BTCUSDT", 1000);

        assertTrue(future.isCompletedExceptionally());
        assertEquals(3, api.calls);
    }

    @Test
    void rateLimited_doesNotRetry() {
        FlakyApi api = new FlakyApi();
        api.failTimes(Integer.MAX_VALUE, new BinanceRateLimitedException(429, Duration.ofSeconds(5)));
        var fetcher = new ExecutorSnapshotFetcher(api, DIRECT, 3, 0, 0);

        CompletableFuture<OrderBookSnapshot> future = fetcher.fetch("BTCUSDT", 1000);

        assertTrue(future.isCompletedExceptionally());
        assertEquals(1, api.calls);
    }

    private static OrderBookSnapshot snapshot() {
        return new OrderBookSnapshot("BTCUSDT", "binance", 100L, List.of(), List.of(), 1000, 1_700_000_000_000L);
    }

    static final class FlakyApi implements BinanceSpotSnapshotApiService {
        int calls;
        private int failsRemaining;
        private RuntimeException failure;
        private OrderBookSnapshot snapshot;

        void failTimes(int n, RuntimeException e) {
            this.failsRemaining = n;
            this.failure = e;
        }

        @Override
        public OrderBookSnapshot load(String symbol, int depthLimit) {
            calls++;
            if (failsRemaining > 0) {
                failsRemaining--;
                throw failure;
            }
            return snapshot;
        }
    }
}
