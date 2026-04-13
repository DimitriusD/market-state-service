package com.trading.mss.port.input;

import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;

public interface ProcessDepthDiffUseCase {

    void process(DepthDiffDto event, KafkaMessageContext context);
}
