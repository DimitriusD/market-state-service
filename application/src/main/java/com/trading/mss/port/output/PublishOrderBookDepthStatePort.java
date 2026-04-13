package com.trading.mss.port.output;

import com.trading.mss.dto.orderbook.OrderBookDepthStateDto;

public interface PublishOrderBookDepthStatePort {
    void publish(OrderBookDepthStateDto event);
}
