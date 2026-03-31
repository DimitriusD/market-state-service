package com.trading.mss.service;

import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.KafkaMessageContext;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ProcessDepthDiffService implements ProcessDepthDiffUseCase {

    private final SymbolStateStorePort stateStore;
    private final DepthDiffBootstrapService bootstrapService;
    private final LiveOrderBookUpdateService liveOrderBookUpdateService;
    private final SymbolStateLifecycleService lifecycleService;
    private final int maxBufferedEvents;

    @Override
    public void process(DepthDiffEvent event, KafkaMessageContext context) {
        if (event == null || event.metadata() == null) {
            log.warn("Received null event or metadata, skipping");
            return;
        }

        var m = event.metadata();
        SymbolState state = stateStore.loadOrCreate(m.symbol(), m.exchange());

        if (state.getMarketType() == null) {
            state.setMarketType(m.marketType());
            state.setInstrumentId(m.instrumentId());
        }

        switch (state.getStatus()) {
            case INIT -> handleInit(event, state, context);
            case BUFFERING_DIFFS -> handleBufferingDiffs(event, state, context);
            case SNAPSHOT_LOADING, APPLYING_BUFFER -> handleBootstrapPhaseEvent(event, state, context);
            case LIVE -> liveOrderBookUpdateService.handleLive(event, state, context);
            case RESYNCING -> handleResyncing(event, state, context);
        }
    }

    private void handleInit(DepthDiffEvent event, SymbolState state, KafkaMessageContext ctx) {
        state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
        if (!bufferEvent(state, event, ctx)) {
            return;
        }
        bootstrapService.startBootstrapIfNeeded(state, ctx);
    }

    private void handleBufferingDiffs(DepthDiffEvent event, SymbolState state, KafkaMessageContext ctx) {
        if (!bufferEvent(state, event, ctx)) {
            return;
        }
        bootstrapService.startBootstrapIfNeeded(state, ctx);
    }

    private void handleBootstrapPhaseEvent(DepthDiffEvent event, SymbolState state, KafkaMessageContext ctx) {
        if (!bufferEvent(state, event, ctx)) {
            return;
        }
        stateStore.save(state);
    }

    private void handleResyncing(DepthDiffEvent event, SymbolState state, KafkaMessageContext ctx) {
        log.info("RESYNCING: symbol={} restarting bootstrap U={} u={} partition={} offset={} key={}",
                state.getSymbol(), event.firstUpdateId(), event.finalUpdateId(),
                ctx.partition(), ctx.offset(), ctx.key());

        lifecycleService.resetStateForBootstrap(state);
        state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
        if (!bufferEvent(state, event, ctx)) {
            return;
        }
        bootstrapService.startBootstrapIfNeeded(state, ctx);
    }

    private boolean bufferEvent(SymbolState state, DepthDiffEvent event, KafkaMessageContext ctx) {
        if (state.getBufferedEvents().size() >= maxBufferedEvents) {
            log.warn("BUFFER_OVERFLOW: symbol={} maxBufferedEvents={} status={} bootstrapInProgress={} partition={} offset={} key={}",
                    state.getSymbol(), maxBufferedEvents, state.getStatus(), state.isBootstrapInProgress(),
                    ctx.partition(), ctx.offset(), ctx.key());
            lifecycleService.enterResyncing(state, "buffer_overflow", ctx);
            return false;
        }

        state.bufferEvent(new BufferedDepthDiff(event, ctx));
        if (state.getFirstBufferedUpdateId() == null) {
            state.setFirstBufferedUpdateId(event.firstUpdateId());
        }
        log.info("BUFFERING: symbol={} U={} u={} bufferSize={} firstBufferedUpdateId={} status={} bootstrapInProgress={}",
                state.getSymbol(), event.firstUpdateId(), event.finalUpdateId(),
                state.getBufferedEvents().size(), state.getFirstBufferedUpdateId(),
                state.getStatus(), state.isBootstrapInProgress());
        return true;
    }
}
