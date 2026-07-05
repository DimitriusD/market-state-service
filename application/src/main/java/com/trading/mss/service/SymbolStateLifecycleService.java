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

import java.time.Clock;

@Slf4j
@RequiredArgsConstructor
public class SymbolStateLifecycleService {

    private final SymbolStateStorePort stateStore;
    private final OrderBookStatusMapper statusMapper;
    private final PublishOrderBookStatusPort statusPublisher;
    private final Clock clock;

    /**
     * Always reached via the snapshot callback command, so there is no "current record" context:
     * the last processed offset is whatever buffer replay recorded via
     * {@code InputPosition.record} (or the previous value/-1 when the buffer was empty — diagnostics
     * only, the status mapper handles it).
     */
    public void enterLiveFromSnapshot(SymbolState state) {
        state.clearBuffer();
        state.getBootstrap().clearFirstBuffered();
        state.setStatus(SymbolStateStatus.LIVE);
        state.setTrusted(true);
        state.getBootstrap().markCompleted();
        // Applying the snapshot counts as an apply, otherwise a symbol LIVE'd with no follow-up
        // diff would trip the staleness watchdog immediately.
        state.setLastAppliedWallTs(clock.millis());
        state.setStaleReported(false);
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
        state.getBootstrap().markCompleted();
        state.getCounters().incrementResync();
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
        state.getBootstrap().markCompleted();
        state.getCounters().incrementResync();
        stateStore.save(state);

        log.warn("RESYNCING: symbol={} reason={} localUpdateId={} partition={} offset={}",
                state.getSymbol(), reason, state.getLocalUpdateId(),
                state.getInput().partitionOr(ctx),
                state.getInput().offsetOr(ctx));

        publishStatus(state, reason, null, ctx, "Entering resync: " + reason);
    }

    public void resetStateForBootstrap(SymbolState state) {
        state.getOrderBook().clear();
        state.clearBuffer();
        state.setLocalUpdateId(-1);
        state.setPreviousLocalUpdateId(null);
        state.setLastSnapshotUpdateId(-1);
        // reset() also bumps the epoch, invalidating any in-flight snapshot fetch: its callback
        // carries the old epoch.
        state.getBootstrap().reset();
        state.setTrusted(false);
    }

    /**
     * Soft staleness: no applies for a while, but the book is internally consistent — report it,
     * leave {@code trusted} untouched (silence may be a quiet market). Edge-triggered via
     * {@code staleReported}; cleared by {@link #clearStaleIfReported} on the next successful apply.
     */
    public void reportSoftStale(SymbolState state, long ageMs) {
        state.setStaleReported(true);
        stateStore.save(state);

        log.warn("STALE_STATE: symbol={} ageMs={} localUpdateId={} — book internally consistent, freshness degraded",
                state.getSymbol(), ageMs, state.getLocalUpdateId());

        publishStatus(state, OrderBookReason.STALE_STATE, null, null,
                "No updates for " + ageMs + "ms; book internally consistent, freshness degraded");
    }

    /** Publishes the recovery counterpart of a previously reported soft-stale status. */
    public void clearStaleIfReported(SymbolState state) {
        if (!state.isStaleReported()) {
            return;
        }
        state.setStaleReported(false);
        stateStore.save(state);

        log.info("STALE_RECOVERED: symbol={} localUpdateId={}", state.getSymbol(), state.getLocalUpdateId());
        publishStatus(state, OrderBookReason.NONE, null, null, "State fresh again");
    }

    private void publishStatus(SymbolState state, OrderBookReason reason, DepthDiffDto event,
                               KafkaMessageContext ctx, String message) {
        statusPublisher.publish(statusMapper.project(state, reason, event, ctx, message));
    }
}
