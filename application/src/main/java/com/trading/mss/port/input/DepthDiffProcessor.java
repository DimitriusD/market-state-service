package com.trading.mss.port.input;

import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;

public interface DepthDiffProcessor {

    void process(DepthDiffDto event, KafkaMessageContext context);
}
