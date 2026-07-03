package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.port.output.SymbolStateStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;

/**
 * Periodic per-symbol tick, delivered as a serialized command by the watchdog. Makes recovery and
 * freshness independent of incoming traffic:
 *
 * <ul>
 *   <li>RESYNCING / BUFFERING_DIFFS: restart bootstrap once the cooldown elapses — even with an
 *       empty buffer (the snapshot alone goes LIVE via the existing empty-buffer path; the
 *       SNAPSHOT_TOO_OLD cross-check does not apply there — the REST snapshot IS current truth).</li>
 *   <li>SNAPSHOT_LOADING: guard against a lost fetch callback via a timeout.</li>
 *   <li>LIVE: two-tier staleness. Soft — status event only, {@code trusted} untouched (silence may
 *       be a quiet market). Hard — full resync: a dead stream degrades into controlled REST
 *       polling (re-bootstrap per ~hard+cooldown, bounded by the rate limiter), keeping the book
 *       fresh from REST. Never just {@code trusted=false}: trust only returns via
 *       {@code enterLiveFromSnapshot}, a merely-untrusted LIVE book would stay unpublished forever.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class SymbolTickService {

    private final SymbolStateStorePort stateStore;
    private final DepthDiffBootstrapService bootstrapService;
    private final SymbolStateLifecycleService lifecycleService;
    private final Clock clock;
    private final long bootstrapCooldownMs;
    private final long snapshotTimeoutMs;
    private final long softStalenessMs;
    private final long hardStalenessMs;

    public void onTick(InstrumentKey key) {
        SymbolState state = stateStore.loadOrCreate(key);
        long now = clock.millis();
        switch (state.getStatus()) {
            case RESYNCING -> restartBootstrapIfCooledDown(state, now, true);
            case BUFFERING_DIFFS -> {
                if (!state.isBootstrapInProgress()) {
                    restartBootstrapIfCooledDown(state, now, false);
                }
            }
            case SNAPSHOT_LOADING -> checkSnapshotTimeout(state, now);
            case LIVE -> checkStaleness(state, now);
            case INIT, APPLYING_BUFFER -> { /* INIT never rests in the store; APPLYING_BUFFER never survives a command */ }
        }
    }

    private void restartBootstrapIfCooledDown(SymbolState state, long now, boolean resetFirst) {
        if (now - state.getLastBootstrapAttemptTs() < bootstrapCooldownMs) {
            return;
        }
        log.info("BOOTSTRAP_FROM_TICK: symbol={} status={} bufferSize={}",
                state.getSymbol(), state.getStatus(), state.getBufferedEvents().size());
        if (resetFirst) {
            lifecycleService.resetStateForBootstrap(state);
            state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
        }
        bootstrapService.startBootstrapIfNeeded(state);
    }

    private void checkSnapshotTimeout(SymbolState state, long now) {
        long inFlightMs = now - state.getLastBootstrapAttemptTs();
        if (inFlightMs <= snapshotTimeoutMs) {
            return;
        }
        log.warn("SNAPSHOT_TIMEOUT: symbol={} inFlightMs={} timeoutMs={} — assuming the fetch callback is lost, entering resync",
                state.getSymbol(), inFlightMs, snapshotTimeoutMs);
        lifecycleService.enterResyncing(state, OrderBookReason.SNAPSHOT_LOAD_FAILED, null);
    }

    private void checkStaleness(SymbolState state, long now) {
        if (!state.isTrusted() || state.getLastAppliedWallTs() <= 0) {
            return;
        }
        long ageMs = now - state.getLastAppliedWallTs();
        if (ageMs > hardStalenessMs) {
            log.warn("HARD_STALE: symbol={} ageMs={} hardThresholdMs={} — entering resync (REST re-bootstrap)",
                    state.getSymbol(), ageMs, hardStalenessMs);
            lifecycleService.enterResyncing(state, OrderBookReason.STALE_STATE, null);
            return;
        }
        if (ageMs > softStalenessMs && !state.isStaleReported()) {
            lifecycleService.reportSoftStale(state, ageMs);
        }
    }
}
