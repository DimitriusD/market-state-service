package com.trading.mss.dto;

public record KafkaMessageContext(
        String key,
        int partition,
        long offset
) {}
