package com.trading.mss.domain.model;

import lombok.Getter;

/**
 * Diagnostic counters surfaced in version/quality/status events. Increment-only: there is no way
 * to set or reset a counter, so published totals stay monotonic for the lifetime of the state.
 */
@Getter
public class SyncCounters {

    private long gapCount = 0;
    private long resyncCount = 0;
    private long duplicateCount = 0;
    private long snapshotRetryCount = 0;

    public void incrementGap() {
        gapCount++;
    }

    public void incrementResync() {
        resyncCount++;
    }

    public void incrementDuplicate() {
        duplicateCount++;
    }

    public void incrementSnapshotRetry() {
        snapshotRetryCount++;
    }
}
