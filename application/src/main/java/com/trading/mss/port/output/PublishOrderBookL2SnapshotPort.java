package com.trading.mss.port.output;

import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;

public interface PublishOrderBookL2SnapshotPort {
    void publish(OrderBookL2SnapshotDto snapshot);
}
