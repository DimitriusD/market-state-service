package com.trading.mss.service;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import lombok.RequiredArgsConstructor;

/**
 * Applies a single depth-diff to a {@link SymbolState}: mutates the order book and advances the
 * state's version/timestamps as one unit. This is the shared core of both the live path and
 * bootstrap buffer replay — previously duplicated in {@code LiveOrderBookUpdateService} and
 * {@code DepthDiffBootstrapService}, where the two copies had already diverged.
 *
 * <p>Deliberately narrow: it knows nothing about wall-clock freshness ({@code lastAppliedWallTs}),
 * persistence ({@code stateStore.save}), or lifecycle transitions. Those differ by context — the
 * live path stamps wall-ts and saves per event; bootstrap replays a burst and stamps once when it
 * goes LIVE — so they stay the caller's explicit responsibility.
 */
@RequiredArgsConstructor
public class OrderBookStateApplier {

    private final OrderBookApplier orderBookApplier;

    public void applyDiffToState(SymbolState state, DepthDiffDto event, KafkaMessageContext ctx) {
        var metadata = event.metadataDto();
        state.setPreviousLocalUpdateId(state.getLocalUpdateId());
        orderBookApplier.applyDiff(state.getOrderBook(), event);
        state.setLocalUpdateId(event.finalUpdateId());
        state.setLastEventExchangeTs(metadata.exchangeTs());
        state.setLastEventReceivedTs(metadata.receivedTs());
        state.setLastEventProcessedTs(metadata.processedTs());
        state.getInput().record(event, ctx);
    }
}
