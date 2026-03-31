package com.trading.mss.port.output;

import com.trading.mss.message.outbound.BboStateEvent;

public interface PublishBboStatePort {
    void publish(BboStateEvent event);
}
