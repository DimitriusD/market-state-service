package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.mapper.OrderBookStatusMapper;
import com.trading.mss.port.output.PublishOrderBookStatusPort;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class SymbolStateLifecycleService {

    private final SymbolStateStorePort stateStore;
    private final OrderBookStatusMapper statusMapper;
    private final PublishOrderBookStatusPort statusPublisher;

    /**
     * Always reached via the snapshot callback command, so there is no "current record" context:
     * the last processed offset is whatever buffer replay recorded via
     * {@code recordInputContext} (or the previous value/-1 when the buffer was empty — diagnostics
     * only, the status mapper handles it).
     */
    public void enterLiveFromSnapshot(SymbolState state) {
        state.clearBuffer();
        state.setFirstBufferedUpdateId(null);
        state.setStatus(SymbolStateStatus.LIVE);
        state.setTrusted(true);
        state.setBootstrapInProgress(false);
        stateStore.save(state);

        OrderBook book = state.getOrderBook();
        log.info("LIVE: symbol={} localUpdateId={} bestBid={} bestAsk={} bidLevels={} askLevels={}",
                state.getSymbol(), state.getLocalUpdateId(),
                book.bestBid(), book.bestAsk(), book.getBids().size(), book.getAsks().size());

        publishStatus(state, OrderBookReason.NONE, null, null, "Order book is live");
    }

    public void enterResyncFromLive(DepthDiffDto event, SymbolState state, KafkaMessageContext ctx, OrderBookReason reason) {
        SymbolStateStatus prevStatus = state.getStatus();
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.setTrusted(false);
        state.setBootstrapInProgress(false);
        state.incrementResyncCount();
        stateStore.save(state);

        log.warn("RESYNC: symbol={} prevStatus={} newStatus=RESYNCING reason={} localUpdateId={} U={} u={} partition={} offset={} key={}",
                state.getSymbol(), prevStatus, reason, state.getLocalUpdateId(),
                event.firstUpdateId(), event.finalUpdateId(),
                ctx.partition(), ctx.offset(), ctx.key());

        publishStatus(state, reason, event, ctx, "Entering resync from live: " + reason);
    }

    /** {@code ctx} may be null when the transition is driven by a snapshot callback or a tick. */
    public void enterResyncing(SymbolState state, OrderBookReason reason, KafkaMessageContext ctx) {
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.setTrusted(false);
        state.setBootstrapInProgress(false);
        state.incrementResyncCount();
        stateStore.save(state);

        log.warn("RESYNCING: symbol={} reason={} localUpdateId={} partition={} offset={}",
                state.getSymbol(), reason, state.getLocalUpdateId(),
                ctx != null ? ctx.partition() : state.getLastProcessedPartition(),
                ctx != null ? ctx.offset() : state.getLastProcessedOffset());

        publishStatus(state, reason, null, ctx, "Entering resync: " + reason);
    }

    public void resetStateForBootstrap(SymbolState state) {
        state.getOrderBook().clear();
        state.clearBuffer();
        state.setLocalUpdateId(-1);
        state.setPreviousLocalUpdateId(null);
        state.setFirstBufferedUpdateId(null);
        state.setLastSnapshotUpdateId(-1);
        state.setBootstrapInProgress(false);
        // Invalidates any in-flight snapshot fetch: its callback carries the old epoch.
        state.incrementBootstrapEpoch();
        state.setTrusted(false);
    }

    private void publishStatus(SymbolState state, OrderBookReason reason, DepthDiffDto event,
                               KafkaMessageContext ctx, String message) {
        statusPublisher.publish(statusMapper.project(state, reason, event, ctx, message));
    }
}
