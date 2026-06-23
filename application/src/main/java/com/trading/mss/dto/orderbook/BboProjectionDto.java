package com.trading.mss.dto.orderbook;

import com.trading.mss.dto.common.PriceLevelDto;

public record BboProjectionDto(
        PriceLevelDto bestBid,
        PriceLevelDto bestAsk,
        String spread,
        String mid
) {}
