package com.trading.mss.service.handler;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.DepthDiffBufferService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BootstrapPhaseStateHandler implements DepthDiffStateHandler {

    private final DepthDiffBufferService bufferService;
    private final SymbolStateStorePort stateStore;

    @Override
    public SymbolStateStatus supportedStatus() {
        return SymbolStateStatus.SNAPSHOT_LOADING;
    }

    @Override
    public void handle(DepthDiffDto event, SymbolState state, KafkaMessageContext context) {
        if (!bufferService.bufferEvent(state, event, context)) {
            return;
        }
        stateStore.save(state);
    }
}
