package com.trading.mss.api;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public class ResilientBinanceSnapshotApiService implements BinanceSpotSnapshotApiService {

    private static final String INSTANCE = "binance-depth-snapshot";
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final BinanceSpotSnapshotApiService delegate;
    private final Clock clock;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final AtomicLong retryAfterUntilEpochMs = new AtomicLong(0);

    public ResilientBinanceSnapshotApiService(BinanceSpotSnapshotApiService delegate,
                                              Clock clock,
                                              BinanceResilienceConfig config) {
        this.delegate = delegate;
        this.clock = clock;
        this.circuitBreaker = CircuitBreaker.of(INSTANCE, CircuitBreakerConfig.custom()
                .failureRateThreshold(config.circuitFailureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.circuitSlidingWindowSize())
                .minimumNumberOfCalls(config.circuitMinimumCalls())
                .waitDurationInOpenState(config.circuitWaitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(config.circuitPermittedCallsInHalfOpen())
                .build());
        this.rateLimiter = RateLimiter.of(INSTANCE, RateLimiterConfig.custom()
                .limitForPeriod(config.rateLimitForPeriod())
                .limitRefreshPeriod(config.rateLimitRefreshPeriod())
                .timeoutDuration(config.rateLimitTimeout())
                .build());
        this.circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("BINANCE_CIRCUIT_BREAKER: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    @Override
    public OrderBookSnapshot load(String symbol, int depthLimit) {
        long now = clock.millis();
        long bannedUntil = retryAfterUntilEpochMs.get();
        if (now < bannedUntil) {
            long remainingMs = bannedUntil - now;
            log.warn("BINANCE_RETRY_AFTER_GATE_OPEN: symbol={} remainingMs={} — failing fast without HTTP",
                    symbol, remainingMs);
            throw new BinanceRateLimitedException(HTTP_TOO_MANY_REQUESTS, Duration.ofMillis(remainingMs));
        }

        Supplier<OrderBookSnapshot> decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
                RateLimiter.decorateSupplier(rateLimiter,
                        () -> delegate.load(symbol, depthLimit)));
        try {
            return decorated.get();
        } catch (BinanceRateLimitedException e) {
            if (e.getRetryAfter() != null) {
                long until = clock.millis() + e.getRetryAfter().toMillis();
                retryAfterUntilEpochMs.accumulateAndGet(until, Math::max);
                log.warn("BINANCE_RATE_LIMITED: symbol={} httpStatus={} retryAfterMs={} — gating all snapshot calls",
                        symbol, e.getHttpStatus(), e.getRetryAfter().toMillis());
            }
            throw e;
        }
    }
}
