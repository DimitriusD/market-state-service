package com.trading.mss.port.output;

import com.trading.mss.dto.orderbook.OrderBookStatusDto;

public interface PublishOrderBookStatusPort {
    void publish(OrderBookStatusDto status);
}
