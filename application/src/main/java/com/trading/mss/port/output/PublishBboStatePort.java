package com.trading.mss.port.output;

import com.trading.mss.dto.orderbook.BboStateDto;

public interface PublishBboStatePort {
    void publish(BboStateDto event);
}
