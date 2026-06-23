package com.trading.mss.mapper;

import com.trading.contracts.orderbook.OrderBookLifecycleStatus;
import com.trading.mss.domain.model.SymbolStateStatus;

public final class OrderBookLifecycleStatusAvroMapper {

    private OrderBookLifecycleStatusAvroMapper() {}

    public static OrderBookLifecycleStatus toWire(SymbolStateStatus status) {
        if (status == null) {
            return OrderBookLifecycleStatus.INIT;
        }
        // Symbol names are aligned 1:1 with the wire enum (INIT, BUFFERING_DIFFS, SNAPSHOT_LOADING,
        // APPLYING_BUFFER, LIVE, RESYNCING); the wire enum also carries DEGRADED/FAILED.
        return OrderBookLifecycleStatus.valueOf(status.name());
    }
}
