package com.trading.mss.domain.model;

/**
 * Internal reason for a degraded, resyncing, failed or special quality state.
 *
 * <p>Mirrors the wire contract {@code com.trading.contracts.orderbook.OrderBookReason} by symbol
 * name so the kafka-adapter can map it with {@code valueOf(name())}. Kept in the application module
 * so the domain never depends on the Avro/contract types.
 */
public enum OrderBookReason {
    NONE,
    GAP_DETECTED,
    DUPLICATE_OR_OLD_EVENT,
    SNAPSHOT_TOO_OLD,
    SNAPSHOT_LOAD_FAILED,
    NO_BRIDGING_EVENT,
    GAP_DURING_BUFFER_REPLAY,
    BUFFER_OVERFLOW,
    CROSSED_BOOK,
    STALE_STATE,
    SLOW_CONSUMER,
    INCOMPLETE_BOOK,
    PUBLISH_FAILED,
    INVARIANT_VIOLATION,
    UNKNOWN_ERROR
}
