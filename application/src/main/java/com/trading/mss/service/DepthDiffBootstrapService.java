package com.trading.mss.service;

import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.SyncDecision;
import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.SymbolExecutorPort;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Deque;
import java.util.concurrent.CompletionException;

/**
 * Snapshot + buffered-diff bootstrap. Fully callback-driven: the snapshot fetch result is delivered
 * as a command on this symbol's serialized executor via {@link #onSnapshotReady}, so bootstrap
 * progresses even when no further diffs arrive for the symbol.
 */
@Slf4j
@RequiredArgsConstructor
public class DepthDiffBootstrapService {

    private final OrderBookApplier orderBookApplier;
    private final OrderBookStateApplier stateApplier;
    private final BinanceSpotSyncPolicy syncPolicy;
    private final AsyncSnapshotPort asyncSnapshotPort;
    private final SymbolStateStorePort stateStore;
    private final SymbolStateLifecycleService lifecycleService;
    private final MarketStatePublisher marketStatePublisher;
    private final SymbolExecutorPort symbolExecutor;
    private final int snapshotDepthLimit;
    private final Clock clock;
    private final long bootstrapCooldownMs;

    public void startBootstrapIfNeeded(SymbolState state) {
        if (state.getBootstrap().isInProgress()) {
            stateStore.save(state);
            return;
        }

        long now = clock.millis();
        long sinceLastAttemptMs = now - state.getBootstrap().getLastAttemptTs();
        if (sinceLastAttemptMs < bootstrapCooldownMs) {
            log.debug("BOOTSTRAP_COOLDOWN: symbol={} sinceLastAttemptMs={} cooldownMs={} bufferSize={} status={} — continuing to buffer",
                    state.getSymbol(), sinceLastAttemptMs, bootstrapCooldownMs,
                    state.getBufferedEvents().size(), state.getStatus());
            stateStore.save(state);
            return;
        }

        state.getBootstrap().markAttempt(now);
        state.setStatus(SymbolStateStatus.SNAPSHOT_LOADING);
        state.getCounters().incrementSnapshotRetry();
        long epoch = state.getBootstrap().incrementEpoch();
        stateStore.save(state);

        log.info("SNAPSHOT_FETCH_SUBMITTED: symbol={} depthLimit={} bufferSize={} epoch={}",
                state.getSymbol(), snapshotDepthLimit, state.getBufferedEvents().size(), epoch);

        InstrumentKey key = state.key();
        asyncSnapshotPort.fetch(key, snapshotDepthLimit)
                .whenCompleteAsync(
                        (snapshot, error) -> onSnapshotReady(key, epoch, snapshot, error),
                        symbolExecutor.executorFor(key));
    }

    /**
     * Runs as a serialized command for {@code key}. Takes the key, not the state: mutable state
     * must not cross the async boundary — it is re-resolved inside the command.
     */
    void onSnapshotReady(InstrumentKey key, long epoch, OrderBookSnapshot snapshot, Throwable error) {
        SymbolState state = stateStore.loadOrCreate(key);
        try {
            if (epoch != state.getBootstrap().getEpoch() || state.getStatus() != SymbolStateStatus.SNAPSHOT_LOADING) {
                log.info("STALE_SNAPSHOT_CALLBACK: symbol={} callbackEpoch={} currentEpoch={} status={} — discarding",
                        state.getSymbol(), epoch, state.getBootstrap().getEpoch(), state.getStatus());
                return;
            }

            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                log.error("Snapshot fetch failed: symbol={} epoch={} error={}",
                        state.getSymbol(), epoch, cause.getMessage());
                lifecycleService.enterResyncing(state, OrderBookReason.SNAPSHOT_LOAD_FAILED, null);
                return;
            }

            if (snapshot == null) {
                log.error("Snapshot fetch returned null: symbol={}", state.getSymbol());
                lifecycleService.enterResyncing(state, OrderBookReason.SNAPSHOT_LOAD_FAILED, null);
                return;
            }

            applySnapshotAndReplay(state, snapshot);
        } catch (RuntimeException e) {
            // whenCompleteAsync would swallow this into a dependent future nobody observes.
            log.error("Unexpected failure applying snapshot: symbol={} epoch={}", state.getSymbol(), epoch, e);
            lifecycleService.enterResyncing(state, OrderBookReason.UNKNOWN_ERROR, null);
        }
    }

    private void applySnapshotAndReplay(SymbolState state, OrderBookSnapshot snapshot) {
        Long firstBufferedUpdateId = state.getBootstrap().getFirstBufferedUpdateId();
        if (firstBufferedUpdateId != null
                && syncPolicy.isSnapshotTooOld(snapshot.lastUpdateId(), firstBufferedUpdateId)) {
            log.warn("Snapshot too old: symbol={} snapshotLastUpdateId={} firstBufferedUpdateId={}",
                    state.getSymbol(), snapshot.lastUpdateId(), firstBufferedUpdateId);
            lifecycleService.enterResyncing(state, OrderBookReason.SNAPSHOT_TOO_OLD, null);
            return;
        }

        state.setStatus(SymbolStateStatus.APPLYING_BUFFER);
        orderBookApplier.applySnapshot(state.getOrderBook(), snapshot);
        state.setLocalUpdateId(snapshot.lastUpdateId());
        state.setPreviousLocalUpdateId(null);
        state.setLastSnapshotUpdateId(snapshot.lastUpdateId());

        int bufferBefore = state.getBufferedEvents().size();
        discardStaleBufferedEvents(state, snapshot.lastUpdateId());
        int bufferAfter = state.getBufferedEvents().size();

        log.info("APPLYING_BUFFER: symbol={} snapshotLastUpdateId={} bufferBefore={} bufferAfter={}",
                state.getSymbol(), snapshot.lastUpdateId(), bufferBefore, bufferAfter);

        if (state.getBufferedEvents().isEmpty()) {
            finishBootstrapToLive(state);
            return;
        }

        DepthDiffDto firstRemainingEvent = state.getBufferedEvents().peekFirst().event();
        if (!syncPolicy.isBridgingEvent(firstRemainingEvent, snapshot.lastUpdateId())) {
            log.warn("No bridging event: symbol={} firstU={} u={} snapshotLastUpdateId={}",
                    state.getSymbol(), firstRemainingEvent.firstUpdateId(), firstRemainingEvent.finalUpdateId(), snapshot.lastUpdateId());
            lifecycleService.enterResyncing(state, OrderBookReason.NO_BRIDGING_EVENT, null);
            return;
        }

        if (!replayBufferedEvents(state)) {
            return;
        }

        finishBootstrapToLive(state);
    }

    private void finishBootstrapToLive(SymbolState state) {
        OrderBook book = state.getOrderBook();
        if (book.isCrossed()) {
            log.warn("CROSSED_BOOK after bootstrap: symbol={} bestBid={} bestAsk={} snapshotLastUpdateId={} — entering resync instead of going LIVE",
                    state.getSymbol(), book.bestBid(), book.bestAsk(), state.getLastSnapshotUpdateId());
            lifecycleService.enterResyncing(state, OrderBookReason.CROSSED_BOOK, null);
            return;
        }
        lifecycleService.enterLiveFromSnapshot(state);
        marketStatePublisher.publishSnapshotIfLive(state, null, null);
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
                case IGNORE -> {
                    state.getCounters().incrementDuplicate();
                    log.info("BUFFER_REPLAY_IGNORE: symbol={} localUpdateId={} U={} u={}",
                            state.getSymbol(), state.getLocalUpdateId(),
                            bufferedEvent.firstUpdateId(), bufferedEvent.finalUpdateId());
                }
                case RESYNC -> {
                    log.warn("BUFFER_REPLAY_RESYNC: symbol={} localUpdateId={} U={} u={}",
                            state.getSymbol(), state.getLocalUpdateId(),
                            bufferedEvent.firstUpdateId(), bufferedEvent.finalUpdateId());
                    state.getCounters().incrementGap();
                    lifecycleService.enterResyncing(state, OrderBookReason.GAP_DURING_BUFFER_REPLAY, bufferedCtx);
                    return false;
                }
                case APPLY -> stateApplier.applyDiffToState(state, bufferedEvent, bufferedCtx);
            }
        }
        return true;
    }
}
