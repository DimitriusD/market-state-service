package com.trading.mss.api;

import lombok.Getter;

import java.time.Duration;

@Getter
public class BinanceRateLimitedException extends RuntimeException {

    private final int httpStatus;
    private final transient Duration retryAfter;

    public BinanceRateLimitedException(int httpStatus, Duration retryAfter) {
        super("Binance rate limited (HTTP " + httpStatus + "), retryAfter=" + retryAfter);
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }
}
