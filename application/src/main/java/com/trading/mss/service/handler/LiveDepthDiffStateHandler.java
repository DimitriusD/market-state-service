package com.trading.mss.service.handler;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.service.LiveOrderBookUpdateService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LiveDepthDiffStateHandler implements DepthDiffStateHandler {

    private final LiveOrderBookUpdateService liveOrderBookUpdateService;

    @Override
    public SymbolStateStatus supportedStatus() {
        return SymbolStateStatus.LIVE;
    }

    @Override
    public void handle(DepthDiffDto event, SymbolState state, KafkaMessageContext context) {
        liveOrderBookUpdateService.handleLive(event, state, context);
    }
}
