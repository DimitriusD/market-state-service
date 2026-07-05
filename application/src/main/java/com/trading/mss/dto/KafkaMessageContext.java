package com.trading.mss.dto;

public record KafkaMessageContext(
        String topic,
        String key,
        int partition,
        long offset
) {}
