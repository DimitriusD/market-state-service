package com.trading.mss.dto.orderbook;

public record OrderBookVersionDto(
        long stateSeq,
        long exchangeUpdateId,
        Long previousExchangeUpdateId,
        long lastSnapshotUpdateId,
        int snapshotDepthLimit,
        int publishedDepth
) {}
