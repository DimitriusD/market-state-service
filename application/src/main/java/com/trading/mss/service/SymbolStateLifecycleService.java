package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.KafkaMessageContext;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class SymbolStateLifecycleService {

    private final SymbolStateStorePort stateStore;

    public void enterLiveFromSnapshot(SymbolState state, KafkaMessageContext ctx, boolean setOffsetFromCurrentContext) {
        state.clearBuffer();
        state.setFirstBufferedUpdateId(null);
        state.setStatus(SymbolStateStatus.LIVE);
        state.setTrusted(true);
        state.setBootstrapInProgress(false);
        if (setOffsetFromCurrentContext) {
            state.setLastProcessedOffset(ctx.offset());
        }
        stateStore.save(state);

        OrderBook book = state.getOrderBook();
        log.info("LIVE: symbol={} localUpdateId={} bestBid={} bestAsk={} bidLevels={} askLevels={}",
                state.getSymbol(), state.getLocalUpdateId(),
                book.bestBid(), book.bestAsk(), book.getBids().size(), book.getAsks().size());
    }

    public void enterResyncFromLive(DepthDiffEvent event, SymbolState state, KafkaMessageContext ctx) {
        SymbolStateStatus prevStatus = state.getStatus();
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.setTrusted(false);
        state.setBootstrapInProgress(false);
        stateStore.save(state);

        log.warn("RESYNC: symbol={} prevStatus={} newStatus=RESYNCING localUpdateId={} U={} u={} partition={} offset={} key={}",
                state.getSymbol(), prevStatus, state.getLocalUpdateId(),
                event.firstUpdateId(), event.finalUpdateId(),
                ctx.partition(), ctx.offset(), ctx.key());
    }

    public void enterResyncing(SymbolState state, String reason, KafkaMessageContext ctx) {
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.setTrusted(false);
        state.setBootstrapInProgress(false);
        stateStore.save(state);

        log.warn("RESYNCING: symbol={} reason={} localUpdateId={} partition={} offset={}",
                state.getSymbol(), reason, state.getLocalUpdateId(), ctx.partition(), ctx.offset());
    }

    public void resetStateForBootstrap(SymbolState state) {
        state.getOrderBook().clear();
        state.clearBuffer();
        state.setLocalUpdateId(-1);
        state.setFirstBufferedUpdateId(null);
        state.setLastSnapshotUpdateId(-1);
        state.setBootstrapInProgress(false);
        state.setTrusted(false);
    }
}
