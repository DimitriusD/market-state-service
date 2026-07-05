package com.trading.mss.dto.orderbook;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.SymbolStateStatus;

public record OrderBookQualityDto(
        SymbolStateStatus lifecycleStatus,
        BookSyncStatus syncStatus,
        boolean trusted,
        OrderBookReason reason,
        boolean snapshotDepthLimited,
        boolean stale,
        boolean crossed,
        boolean incompleteBook,
        long stateAgeMs,
        long publishLagMs,
        int bufferedDiffCount,
        long gapCount,
        long resyncCount,
        long duplicateCount,
        long snapshotRetryCount
) {}
