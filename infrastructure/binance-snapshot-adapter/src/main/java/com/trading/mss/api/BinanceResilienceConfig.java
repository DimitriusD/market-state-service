package com.trading.mss.api;

import java.time.Duration;

public record BinanceResilienceConfig(
        int rateLimitForPeriod,
        Duration rateLimitRefreshPeriod,
        Duration rateLimitTimeout,
        float circuitFailureRateThreshold,
        int circuitSlidingWindowSize,
        int circuitMinimumCalls,
        Duration circuitWaitInOpenState,
        int circuitPermittedCallsInHalfOpen
) {
}
