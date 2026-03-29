package com.trading.mss.message.inbound;

public record KafkaMessageContext(
        String key,
        int partition,
        long offset
) {}
