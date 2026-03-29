package com.trading.mss.port.input;

import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.KafkaMessageContext;

public interface ProcessDepthDiffUseCase {

    void process(DepthDiffEvent event, KafkaMessageContext context);
}
