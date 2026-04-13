package com.trading.mss.dto.orderbook;

import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;

import java.util.List;

public record OrderBookDepthStateDto(
        MetadataDto metadata,
        int publishedLevels,
        List<PriceLevelDto> bidLevels,
        List<PriceLevelDto> askLevels,
        BookSyncStatus syncStatus
) {
}
