package com.trading.mss.domain.model;

import lombok.Getter;

/**
 * Bootstrap progress for one symbol: whether a snapshot fetch is in flight, when it was last
 * attempted, the fetch epoch, and the first buffered update id used by the snapshot-too-old check.
 *
 * <p>The epoch is incremented on every snapshot-fetch submission and on every bootstrap reset; a
 * snapshot callback carrying an older epoch is stale (superseded fetch) and must be discarded.
 * Plain long on purpose: only ever touched from the symbol's serialized commands.
 */
@Getter
public class BootstrapProgress {

    private boolean inProgress = false;
    private long epoch = 0;
    private long lastAttemptTs = 0;
    private Long firstBufferedUpdateId = null;

    /** A snapshot fetch is being submitted now. */
    public void markAttempt(long nowMs) {
        inProgress = true;
        lastAttemptTs = nowMs;
    }

    /** The current bootstrap ended — successfully (LIVE) or not (resync). */
    public void markCompleted() {
        inProgress = false;
    }

    public long incrementEpoch() {
        return ++epoch;
    }

    /** Remembers the first update id ever buffered for the current bootstrap round. */
    public void noteFirstBuffered(long firstUpdateId) {
        if (firstBufferedUpdateId == null) {
            firstBufferedUpdateId = firstUpdateId;
        }
    }

    public void clearFirstBuffered() {
        firstBufferedUpdateId = null;
    }

    /** Full reset before a fresh bootstrap; bumps the epoch so any in-flight fetch is invalidated. */
    public void reset() {
        inProgress = false;
        firstBufferedUpdateId = null;
        epoch++;
    }
}
