package com.trading.mss.service;

import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.SyncDecision;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
@RequiredArgsConstructor
public class DepthDiffBootstrapService {

    private final OrderBookApplier orderBookApplier;
    private final BinanceSpotSyncPolicy syncPolicy;
    private final AsyncSnapshotPort asyncSnapshotPort;
    private final SymbolStateStorePort stateStore;
    private final SymbolStateLifecycleService lifecycleService;
    private final MarketStatePublisher marketStatePublisher;
    private final int snapshotDepthLimit;
    private final Clock clock;
    private final long bootstrapCooldownMs;

    public void startBootstrapIfNeeded(SymbolState state, KafkaMessageContext ctx) {
        if (state.isBootstrapInProgress()) {
            stateStore.save(state);
            return;
        }

        long now = clock.millis();
        long sinceLastAttemptMs = now - state.getLastBootstrapAttemptTs();
        if (sinceLastAttemptMs < bootstrapCooldownMs) {
            log.debug("BOOTSTRAP_COOLDOWN: symbol={} sinceLastAttemptMs={} cooldownMs={} bufferSize={} status={} — continuing to buffer",
                    state.getSymbol(), sinceLastAttemptMs, bootstrapCooldownMs,
                    state.getBufferedEvents().size(), state.getStatus());
            stateStore.save(state);
            return;
        }

        state.setLastBootstrapAttemptTs(now);
        state.setBootstrapInProgress(true);
        state.setStatus(SymbolStateStatus.SNAPSHOT_LOADING);
        state.setPendingSnapshot(asyncSnapshotPort.fetch(state.getSymbol(), snapshotDepthLimit));
        stateStore.save(state);

        log.info("SNAPSHOT_FETCH_SUBMITTED: symbol={} depthLimit={} bufferSize={}",
                state.getSymbol(), snapshotDepthLimit, state.getBufferedEvents().size());

        tryApplyPendingSnapshot(state, ctx);
    }

    public void tryApplyPendingSnapshot(SymbolState state, KafkaMessageContext ctx) {
        CompletableFuture<OrderBookSnapshot> future = state.getPendingSnapshot();
        if (future == null || !future.isDone()) {
            return;
        }
        state.setPendingSnapshot(null);

        OrderBookSnapshot snapshot;
        try {
            snapshot = future.join();
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            log.error("Snapshot fetch failed: symbol={} error={}", state.getSymbol(), cause.getMessage());
            lifecycleService.enterResyncing(state, "snapshot_load_failed", ctx);
            return;
        }

        if (snapshot == null) {
            log.error("Snapshot fetch returned null: symbol={}", state.getSymbol());
            lifecycleService.enterResyncing(state, "snapshot_load_failed", ctx);
            return;
        }

        applySnapshotAndReplay(state, snapshot, ctx);
    }

    private void applySnapshotAndReplay(SymbolState state, OrderBookSnapshot snapshot, KafkaMessageContext ctx) {
        Long firstBufferedUpdateId = state.getFirstBufferedUpdateId();
        if (firstBufferedUpdateId != null
                && syncPolicy.isSnapshotTooOld(snapshot.lastUpdateId(), firstBufferedUpdateId)) {
            log.warn("Snapshot too old: symbol={} snapshotLastUpdateId={} firstBufferedUpdateId={}",
                    state.getSymbol(), snapshot.lastUpdateId(), firstBufferedUpdateId);
            lifecycleService.enterResyncing(state, "snapshot_too_old", ctx);
            return;
        }

        state.setStatus(SymbolStateStatus.APPLYING_BUFFER);
        orderBookApplier.applySnapshot(state.getOrderBook(), snapshot);
        state.setLocalUpdateId(snapshot.lastUpdateId());
        state.setLastSnapshotUpdateId(snapshot.lastUpdateId());

        int bufferBefore = state.getBufferedEvents().size();
        discardStaleBufferedEvents(state, snapshot.lastUpdateId());
        int bufferAfter = state.getBufferedEvents().size();

        log.info("APPLYING_BUFFER: symbol={} snapshotLastUpdateId={} bufferBefore={} bufferAfter={}",
                state.getSymbol(), snapshot.lastUpdateId(), bufferBefore, bufferAfter);

        if (state.getBufferedEvents().isEmpty()) {
            finishBootstrapToLive(state, ctx, true);
            return;
        }

        DepthDiffDto firstRemainingEvent = state.getBufferedEvents().peekFirst().event();
        if (!syncPolicy.isBridgingEvent(firstRemainingEvent, snapshot.lastUpdateId())) {
            log.warn("No bridging event: symbol={} firstU={} u={} snapshotLastUpdateId={}",
                    state.getSymbol(), firstRemainingEvent.firstUpdateId(), firstRemainingEvent.finalUpdateId(), snapshot.lastUpdateId());
            lifecycleService.enterResyncing(state, "no_bridging_event", ctx);
            return;
        }

        if (!replayBufferedEvents(state)) {
            return;
        }

        finishBootstrapToLive(state, ctx, false);
    }

    private void finishBootstrapToLive(SymbolState state, KafkaMessageContext ctx, boolean setOffsetFromCurrentContext) {
        OrderBook book = state.getOrderBook();
        if (book.isCrossed()) {
            log.warn("CROSSED_BOOK after bootstrap: symbol={} bestBid={} bestAsk={} snapshotLastUpdateId={} — entering resync instead of going LIVE",
                    state.getSymbol(), book.bestBid(), book.bestAsk(), state.getLastSnapshotUpdateId());
            lifecycleService.enterResyncing(state, "crossed_book_after_bootstrap", ctx);
            return;
        }
        lifecycleService.enterLiveFromSnapshot(state, ctx, setOffsetFromCurrentContext);
        marketStatePublisher.publishProjectedStateIfLive(state);
    }

    public void discardStaleBufferedEvents(SymbolState state, long snapshotLastUpdateId) {
        Deque<BufferedDepthDiff> buffer = state.getBufferedEvents();
        while (!buffer.isEmpty() && syncPolicy.shouldDiscardBufferedEvent(buffer.peekFirst().event(), snapshotLastUpdateId)) {
            buffer.pollFirst();
        }
    }

    public boolean replayBufferedEvents(SymbolState state) {
        while (!state.getBufferedEvents().isEmpty()) {
            BufferedDepthDiff buffered = state.getBufferedEvents().pollFirst();
            DepthDiffDto bufferedEvent = buffered.event();
            KafkaMessageContext bufferedCtx = buffered.context();
            SyncDecision decision = syncPolicy.evaluate(bufferedEvent, state);

            switch (decision) {
                case IGNORE -> log.info("BUFFER_REPLAY_IGNORE: symbol={} localUpdateId={} U={} u={}",
                        state.getSymbol(), state.getLocalUpdateId(),
                        bufferedEvent.firstUpdateId(), bufferedEvent.finalUpdateId());
                case RESYNC -> {
                    log.warn("BUFFER_REPLAY_RESYNC: symbol={} localUpdateId={} U={} u={}",
                            state.getSymbol(), state.getLocalUpdateId(),
                            bufferedEvent.firstUpdateId(), bufferedEvent.finalUpdateId());
                    lifecycleService.enterResyncing(state, "gap_during_buffer_replay", bufferedCtx);
                    return false;
                }
                case APPLY -> applyDepthDiffToState(state, bufferedEvent, bufferedCtx);
            }
        }
        return true;
    }

    private void applyDepthDiffToState(SymbolState state, DepthDiffDto event, KafkaMessageContext ctx) {
        var metadata = event.metadataDto();
        orderBookApplier.applyDiff(state.getOrderBook(), event);
        state.setLocalUpdateId(event.finalUpdateId());
        state.setLastProcessedOffset(ctx.offset());
        state.setLastEventExchangeTs(metadata.exchangeTs());
        state.setLastEventReceivedTs(metadata.receivedTs());
        state.setLastEventProcessedTs(metadata.processedTs());
    }
}
