package com.trading.mss.domain.model;

import com.trading.mss.dto.common.PriceLevelDto;

import java.util.List;

public record OrderBookSnapshot(
        String symbol,
        String venue,
        long lastUpdateId,
        List<PriceLevelDto> bids,
        List<PriceLevelDto> asks,
        int depthLimit,
        long loadedAt
) {}
