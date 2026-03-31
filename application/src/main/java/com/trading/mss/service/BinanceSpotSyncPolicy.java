package com.trading.mss.service;

import com.trading.mss.domain.model.SyncDecision;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.message.inbound.DepthDiffEvent;

public class BinanceSpotSyncPolicy {
    public SyncDecision evaluate(DepthDiffEvent event, SymbolState state) {
        long localUid = state.getLocalUpdateId();

        if (localUid < 0) {
            return SyncDecision.APPLY;
        }

        if (event.finalUpdateId() <= localUid) {
            return SyncDecision.IGNORE;
        }

        if (event.firstUpdateId() > localUid + 1) {
            return SyncDecision.RESYNC;
        }

        return SyncDecision.APPLY;
    }

    public boolean isSnapshotTooOld(long snapshotLastUpdateId, long firstBufferedUpdateId) {
        return snapshotLastUpdateId < firstBufferedUpdateId - 1;
    }

    public boolean shouldDiscardBufferedEvent(DepthDiffEvent event, long snapshotLastUpdateId) {
        return event.finalUpdateId() <= snapshotLastUpdateId;
    }

    public boolean isBridgingEvent(DepthDiffEvent event, long snapshotLastUpdateId) {
        long requiredNextUpdateId = snapshotLastUpdateId + 1;
        return event.firstUpdateId() <= requiredNextUpdateId
                && requiredNextUpdateId <= event.finalUpdateId();
    }
}
