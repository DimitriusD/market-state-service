package com.trading.mss.mapper;

import com.trading.contracts.orderbook.BookSyncStatus;

public final class BookSyncStatusAvroMapper {

    private BookSyncStatusAvroMapper() {}

    public static BookSyncStatus toWire(com.trading.common.enums.BookSyncStatus app) {
        if (app == null) {
            return BookSyncStatus.OUT_OF_SYNC;
        }
        return BookSyncStatus.valueOf(app.name());
    }
}
