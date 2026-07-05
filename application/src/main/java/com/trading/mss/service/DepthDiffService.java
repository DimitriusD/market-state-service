package com.trading.mss.service;

import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.input.DepthDiffProcessor;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.handler.DepthDiffStateHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DepthDiffService implements DepthDiffProcessor {

    private final SymbolStateStorePort stateStore;
    private final DepthDiffStateHandlerRegistry handlerRegistry;

    @Override
    public void process(DepthDiffDto event, KafkaMessageContext context) {
        if (event == null || event.metadataDto() == null) {
            log.warn("Received null event or metadata, skipping");
            return;
        }

        var metadata = event.metadataDto();
        InstrumentKey key = InstrumentKey.of(metadata);
        SymbolState state = stateStore.loadOrCreate(key);

        if (!state.key().equals(key)) {
            log.error("Instrument identity mismatch: stateKey={} eventKey={} eventId={} — skipping event",
                    state.key(), key, metadata.eventId());
            return;
        }

        if (state.getBase() == null) {
            state.setBase(metadata.base());
        }
        if (state.getQuote() == null) {
            state.setQuote(metadata.quote());
        }

        handlerRegistry.getHandler(state.getStatus()).handle(event, state, context);
    }
}
