package com.trading.mss.dto.orderbook;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;

public record BboStateDto(
        MetadataDto metadata,
        PriceLevelDto bestBid,
        PriceLevelDto bestAsk,
        String spread,
        String mid,
        BookSyncStatus syncStatus
) {}
