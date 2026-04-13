package com.trading.mss.service.handler;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.service.DepthDiffBootstrapService;
import com.trading.mss.service.DepthDiffBufferService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BufferingDiffsStateHandler implements DepthDiffStateHandler {

    private final DepthDiffBufferService bufferService;
    private final DepthDiffBootstrapService bootstrapService;

    @Override
    public SymbolStateStatus supportedStatus() {
        return SymbolStateStatus.BUFFERING_DIFFS;
    }

    @Override
    public void handle(DepthDiffDto event, SymbolState state, KafkaMessageContext context) {
        if (!bufferService.bufferEvent(state, event, context)) {
            return;
        }
        bootstrapService.startBootstrapIfNeeded(state, context);
    }
}
