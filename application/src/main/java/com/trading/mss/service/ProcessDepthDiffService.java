package com.trading.mss.service;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.handler.DepthDiffStateHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ProcessDepthDiffService implements ProcessDepthDiffUseCase {

    private final SymbolStateStorePort stateStore;
    private final DepthDiffStateHandlerRegistry handlerRegistry;

    @Override
    public void process(DepthDiffDto event, KafkaMessageContext context) {
        if (event == null || event.metadataDto() == null) {
            log.warn("Received null event or metadata, skipping");
            return;
        }

        var metadata = event.metadataDto();
        SymbolState state = stateStore.loadOrCreate(metadata.symbol(), metadata.exchange());

        if (state.getMarketType() == null) {
            state.setMarketType(metadata.marketType());
            state.setInstrumentId(metadata.instrumentId());
            state.setBase(metadata.base());
            state.setQuote(metadata.quote());
        }

        handlerRegistry.getHandler(state.getStatus()).handle(event, state, context);
    }
}
