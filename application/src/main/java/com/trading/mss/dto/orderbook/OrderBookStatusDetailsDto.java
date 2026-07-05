package com.trading.mss.dto.orderbook;

import com.trading.mss.dto.common.PriceLevelDto;

public record OrderBookStatusDetailsDto(
        long localUpdateId,
        Long previousLocalUpdateId,
        long lastSnapshotUpdateId,
        Long firstBufferedUpdateId,
        Long lastEventFirstUpdateId,
        Long lastEventFinalUpdateId,
        int bufferedDiffCount,
        PriceLevelDto bestBid,
        PriceLevelDto bestAsk,
        String inputTopic,
        String inputKey,
        int inputPartition,
        long inputOffset,
        long stateAgeMs,
        long gapCount,
        long resyncCount,
        long duplicateCount,
        long snapshotRetryCount
) {}
