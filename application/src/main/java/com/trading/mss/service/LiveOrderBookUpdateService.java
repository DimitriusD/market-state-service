package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.SyncDecision;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LiveOrderBookUpdateService {

    private final OrderBookApplier orderBookApplier;
    private final BinanceSpotSyncPolicy syncPolicy;
    private final SymbolStateStorePort stateStore;
    private final SymbolStateLifecycleService lifecycleService;
    private final MarketStatePublisher marketStatePublisher;

    public void handleLive(DepthDiffDto event, SymbolState state, KafkaMessageContext ctx) {
        SyncDecision decision = syncPolicy.evaluate(event, state);
        switch (decision) {
            case IGNORE -> logIgnore(event, state, ctx);
            case RESYNC -> lifecycleService.enterResyncFromLive(event, state, ctx);
            case APPLY -> applyLiveEvent(event, state, ctx);
        }
    }

    private void logIgnore(DepthDiffDto event, SymbolState state, KafkaMessageContext ctx) {
        log.info("IGNORE: symbol={} localUpdateId={} U={} u={} partition={} offset={} key={}",
                state.getSymbol(), state.getLocalUpdateId(),
                event.firstUpdateId(), event.finalUpdateId(),
                ctx.partition(), ctx.offset(), ctx.key());
    }

    private void applyLiveEvent(DepthDiffDto event, SymbolState state, KafkaMessageContext ctx) {
        long prevLocalUpdateId = state.getLocalUpdateId();
        applyDepthDiffToState(state, event, ctx, true);

        OrderBook book = state.getOrderBook();
        log.info("APPLY: symbol={} venue={} status={} trusted={} "
                        + "prevLocalUpdateId={} localUpdateId={} U={} u={} "
                        + "bidLevels={} askLevels={} bestBid={} bestAsk={} "
                        + "partition={} offset={} key={}",
                state.getSymbol(), state.getVenue(), state.getStatus(), state.isTrusted(),
                prevLocalUpdateId, state.getLocalUpdateId(),
                event.firstUpdateId(), event.finalUpdateId(),
                book.getBids().size(), book.getAsks().size(), book.bestBid(), book.bestAsk(),
                ctx.partition(), ctx.offset(), ctx.key());

        marketStatePublisher.publishProjectedStateIfLive(state);
    }

    void applyDepthDiffToState(SymbolState state, DepthDiffDto event, KafkaMessageContext ctx, boolean save) {
        var metadata = event.metadataDto();
        orderBookApplier.applyDiff(state.getOrderBook(), event);
        state.setLocalUpdateId(event.finalUpdateId());
        state.setLastProcessedOffset(ctx.offset());
        state.setLastEventExchangeTs(metadata.exchangeTs());
        state.setLastEventReceivedTs(metadata.receivedTs());
        state.setLastEventProcessedTs(metadata.processedTs());
        if (save) {
            stateStore.save(state);
        }
    }
}
