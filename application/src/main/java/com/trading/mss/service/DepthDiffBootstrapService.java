package com.trading.mss.service;

import com.trading.mss.domain.model.BufferedDepthDiff;
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

    /**
     * Phase A — runs on the consumer thread and does NOT block. Submits the snapshot fetch to the
     * background fetcher and returns; incoming diffs keep buffering meanwhile (handled by
     * {@code BootstrapPhaseStateHandler}). The result is applied later via
     * {@link #tryApplyPendingSnapshot}.
     */
    public void startBootstrapIfNeeded(SymbolState state, KafkaMessageContext ctx) {
        if (state.isBootstrapInProgress()) {
            stateStore.save(state);
            return;
        }

        long now = clock.millis();
        long sinceLastAttemptMs = now - state.getLastBootstrapAttemptTs();
        if (sinceLastAttemptMs < bootstrapCooldownMs) {
            // Throttle bootstrap restarts per symbol so a failing snapshot (e.g. Binance 429/418)
            // cannot trigger a fresh fetch on every incoming diff. Keep buffering instead;
            // a later event past the cooldown will retry the snapshot.
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

        // If the fetch already completed (fast path / caller-runs executor), apply now without
        // waiting for the next event.
        tryApplyPendingSnapshot(state, ctx);
    }

    /**
     * Phase B — runs on the consumer thread. If the async snapshot fetch has completed, consume the
     * result and finish the bootstrap (apply snapshot + replay buffer → LIVE, or RESYNCING on any
     * failure / gap). No-op while the fetch is still in flight.
     */
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
            lifecycleService.enterLiveFromSnapshot(state, ctx, true);
            marketStatePublisher.publishProjectedStateIfLive(state);
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

        lifecycleService.enterLiveFromSnapshot(state, ctx, false);
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
