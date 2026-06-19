package com.trading.mss.api;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResilientBinanceSnapshotApiServiceTest {

    private static BinanceResilienceConfig config() {
        return new BinanceResilienceConfig(
                100, Duration.ofSeconds(60), Duration.ZERO,
                50f, 10, 5, Duration.ofSeconds(30), 2);
    }

    @Test
    void retryAfter_gatesSubsequentCallsWithoutHittingDelegate() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        CountingDelegate delegate = new CountingDelegate();
        delegate.failWith(new BinanceRateLimitedException(429, Duration.ofSeconds(10)));
        var service = new ResilientBinanceSnapshotApiService(delegate, clock, config());

        // First call reaches the delegate, throws 429, and arms the Retry-After gate.
        assertThrows(BinanceRateLimitedException.class, () -> service.load("BTCUSDT", 1000));
        assertEquals(1, delegate.calls);

        // While the gate is open, further calls fail fast without any HTTP to Binance.
        assertThrows(BinanceRateLimitedException.class, () -> service.load("BTCUSDT", 1000));
        assertThrows(BinanceRateLimitedException.class, () -> service.load("ETHUSDT", 1000));
        assertEquals(1, delegate.calls);

        // Once the advised window elapses, the delegate is consulted again.
        clock.advance(Duration.ofSeconds(10).toMillis() + 1);
        delegate.succeedWith(snapshot());
        OrderBookSnapshot result = service.load("BTCUSDT", 1000);

        assertEquals(2, delegate.calls);
        assertNotNull(result);
    }

    @Test
    void rateLimitWithoutRetryAfterHeader_doesNotArmGate() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        CountingDelegate delegate = new CountingDelegate();
        delegate.failWith(new BinanceRateLimitedException(429, null));
        var service = new ResilientBinanceSnapshotApiService(delegate, clock, config());

        assertThrows(BinanceRateLimitedException.class, () -> service.load("BTCUSDT", 1000));
        // No Retry-After ⇒ no gate ⇒ the next call still reaches the delegate (the circuit breaker
        // handles the no-header case once the failure rate threshold is crossed).
        assertThrows(BinanceRateLimitedException.class, () -> service.load("BTCUSDT", 1000));
        assertEquals(2, delegate.calls);
    }

    private static OrderBookSnapshot snapshot() {
        return new OrderBookSnapshot("BTCUSDT", "binance", 100L, List.of(), List.of(), 1000, 1_700_000_000_000L);
    }

    static final class CountingDelegate implements BinanceSpotSnapshotApiService {
        int calls;
        private RuntimeException failure;
        private OrderBookSnapshot snapshot;

        void failWith(RuntimeException failure) {
            this.failure = failure;
            this.snapshot = null;
        }

        void succeedWith(OrderBookSnapshot snapshot) {
            this.snapshot = snapshot;
            this.failure = null;
        }

        @Override
        public OrderBookSnapshot load(String symbol, int depthLimit) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return snapshot;
        }
    }

    static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long startMillis) {
            this.millis = startMillis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
