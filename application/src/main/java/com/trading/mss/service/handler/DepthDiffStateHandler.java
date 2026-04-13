package com.trading.mss.service.handler;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;

public interface DepthDiffStateHandler {

    SymbolStateStatus supportedStatus();

    void handle(DepthDiffDto event, SymbolState state, KafkaMessageContext context);
}
