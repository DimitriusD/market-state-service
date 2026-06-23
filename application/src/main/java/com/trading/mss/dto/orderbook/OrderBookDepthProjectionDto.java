package com.trading.mss.dto.orderbook;

import com.trading.mss.dto.common.PriceLevelDto;

import java.util.List;

public record OrderBookDepthProjectionDto(
        List<PriceLevelDto> bids,
        List<PriceLevelDto> asks
) {}
