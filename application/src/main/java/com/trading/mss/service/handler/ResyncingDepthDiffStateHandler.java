package com.trading.mss.service.handler;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.service.DepthDiffBootstrapService;
import com.trading.mss.service.DepthDiffBufferService;
import com.trading.mss.service.SymbolStateLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ResyncingDepthDiffStateHandler implements DepthDiffStateHandler {

    private final DepthDiffBufferService bufferService;
    private final DepthDiffBootstrapService bootstrapService;
    private final SymbolStateLifecycleService lifecycleService;

    @Override
    public SymbolStateStatus supportedStatus() {
        return SymbolStateStatus.RESYNCING;
    }

    @Override
    public void handle(DepthDiffDto event, SymbolState state, KafkaMessageContext context) {
        log.info("RESYNCING: symbol={} restarting bootstrap U={} u={} partition={} offset={} key={}",
                state.getSymbol(), event.firstUpdateId(), event.finalUpdateId(),
                context.partition(), context.offset(), context.key());

        lifecycleService.resetStateForBootstrap(state);
        state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
        if (!bufferService.bufferEvent(state, event, context)) {
            return;
        }
        bootstrapService.startBootstrapIfNeeded(state, context);
    }
}
