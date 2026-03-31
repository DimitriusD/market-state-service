package com.trading.mss.service;

import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.SyncDecision;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.KafkaMessageContext;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Deque;

@Slf4j
@RequiredArgsConstructor
public class DepthDiffBootstrapService {

    private static final int MAX_SNAPSHOT_RETRIES = 3;

    private final OrderBookApplier orderBookApplier;
    private final BinanceSpotSyncPolicy syncPolicy;
    private final BinanceSpotSnapshotApiService snapshotPort;
    private final SymbolStateStorePort stateStore;
    private final SymbolStateLifecycleService lifecycleService;
    private final MarketStatePublisher marketStatePublisher;
    private final int snapshotDepthLimit;

    public void startBootstrapIfNeeded(SymbolState state, KafkaMessageContext ctx) {
        if (state.isBootstrapInProgress()) {
            stateStore.save(state);
            return;
        }

        state.setBootstrapInProgress(true);
        stateStore.save(state);

        runBootstrap(state, ctx);
    }

    public void runBootstrap(SymbolState state, KafkaMessageContext ctx) {
        OrderBookSnapshot snapshot = loadFreshSnapshot(state, ctx);
        if (snapshot == null) {
            state.setBootstrapInProgress(false);
            stateStore.save(state);
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

        DepthDiffEvent firstRemainingEvent = state.getBufferedEvents().peekFirst().event();
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

    public OrderBookSnapshot loadFreshSnapshot(SymbolState state, KafkaMessageContext ctx) {
        state.setStatus(SymbolStateStatus.SNAPSHOT_LOADING);
        stateStore.save(state);

        OrderBookSnapshot snapshot = null;
        for (int attempt = 1; attempt <= MAX_SNAPSHOT_RETRIES; attempt++) {
            try {
                snapshot = snapshotPort.load(state.getSymbol(), snapshotDepthLimit);
            } catch (Exception e) {
                log.error("Snapshot load failed: symbol={} attempt={}/{} error={}",
                        state.getSymbol(), attempt, MAX_SNAPSHOT_RETRIES, e.getMessage());
                if (attempt == MAX_SNAPSHOT_RETRIES) {
                    lifecycleService.enterResyncing(state, "snapshot_load_failed", ctx);
                    return null;
                }
                continue;
            }

            log.info("SNAPSHOT_LOADED: symbol={} attempt={}/{} depthLimit={} snapshotLastUpdateId={}",
                    state.getSymbol(), attempt, MAX_SNAPSHOT_RETRIES,
                    snapshotDepthLimit, snapshot.lastUpdateId());

            Long firstBufferedUpdateId = state.getFirstBufferedUpdateId();
            if (firstBufferedUpdateId == null
                    || !syncPolicy.isSnapshotTooOld(snapshot.lastUpdateId(), firstBufferedUpdateId)) {
                return snapshot;
            }

            log.warn("Snapshot too old: symbol={} snapshotLastUpdateId={} firstBufferedUpdateId={}",
                    state.getSymbol(), snapshot.lastUpdateId(), state.getFirstBufferedUpdateId());

            if (attempt == MAX_SNAPSHOT_RETRIES) {
                lifecycleService.enterResyncing(state, "snapshot_too_old_after_retries", ctx);
                return null;
            }
        }

        return snapshot;
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
            DepthDiffEvent bufferedEvent = buffered.event();
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

    private void applyDepthDiffToState(SymbolState state, DepthDiffEvent event, KafkaMessageContext ctx) {
        var metadata = event.metadata();
        orderBookApplier.applyDiff(state.getOrderBook(), event);
        state.setLocalUpdateId(event.finalUpdateId());
        state.setLastProcessedOffset(ctx.offset());
        state.setLastEventExchangeTs(metadata.exchangeTs());
        state.setLastEventReceivedTs(metadata.receivedTs());
        state.setLastEventProcessedTs(metadata.processedTs());
    }
}
