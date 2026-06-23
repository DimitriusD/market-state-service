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

        publishStatus(state, OrderBookReason.NONE, null, ctx, "Order book is live");
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

    public void enterResyncing(SymbolState state, OrderBookReason reason, KafkaMessageContext ctx) {
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.setTrusted(false);
        state.setBootstrapInProgress(false);
        state.incrementResyncCount();
        stateStore.save(state);

        log.warn("RESYNCING: symbol={} reason={} localUpdateId={} partition={} offset={}",
                state.getSymbol(), reason, state.getLocalUpdateId(), ctx.partition(), ctx.offset());

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
        state.setPendingSnapshot(null);
        state.setTrusted(false);
    }

    private void publishStatus(SymbolState state, OrderBookReason reason, DepthDiffDto event,
                               KafkaMessageContext ctx, String message) {
        statusPublisher.publish(statusMapper.project(state, reason, event, ctx, message));
    }
}
