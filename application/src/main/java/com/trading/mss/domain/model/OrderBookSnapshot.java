package com.trading.mss.domain.model;

import com.trading.mss.message.inbound.PriceLevel;

import java.util.List;

public record OrderBookSnapshot(
        String symbol,
        String venue,
        long lastUpdateId,
        List<PriceLevel> bids,
        List<PriceLevel> asks,
        int depthLimit,
        long loadedAt
) {}
