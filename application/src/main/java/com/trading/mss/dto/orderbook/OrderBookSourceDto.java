package com.trading.mss.dto.orderbook;

public record OrderBookSourceDto(
        String inputTopic,
        String inputKey,
        int inputPartition,
        long inputOffset,
        String upstreamEventId,
        Long inputFirstUpdateId,
        Long inputFinalUpdateId
) {}
