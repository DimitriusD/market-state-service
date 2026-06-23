package com.trading.mss.mapper;

import com.trading.contracts.orderbook.OrderBookReason;

public final class OrderBookReasonAvroMapper {

    private OrderBookReasonAvroMapper() {}

    public static OrderBookReason toWire(com.trading.mss.domain.model.OrderBookReason reason) {
        if (reason == null) {
            return OrderBookReason.NONE;
        }
        // Internal reason mirrors the wire enum symbol-for-symbol.
        return OrderBookReason.valueOf(reason.name());
    }
}
