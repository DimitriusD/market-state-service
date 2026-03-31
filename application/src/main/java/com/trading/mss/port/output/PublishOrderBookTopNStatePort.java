package com.trading.mss.port.output;

import com.trading.mss.message.outbound.OrderBookTopNStateEvent;

public interface PublishOrderBookTopNStatePort {
    void publish(OrderBookTopNStateEvent event);
}
