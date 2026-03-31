package com.trading.mss.domain.model;

public enum SymbolStateStatus {
    INIT,
    BUFFERING_DIFFS,
    SNAPSHOT_LOADING,
    APPLYING_BUFFER,
    LIVE,
    RESYNCING
}
