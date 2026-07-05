package com.trading.mss.dto.orderbook;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.common.MetadataDto;

public record OrderBookStatusDto(
        MetadataDto metadata,
        SymbolStateStatus lifecycleStatus,
        BookSyncStatus syncStatus,
        boolean trusted,
        OrderBookReason reason,
        OrderBookStatusDetailsDto details,
        String message
) {}
