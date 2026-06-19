package com.trading.mss.port.output;

import com.trading.mss.domain.model.OrderBookSnapshot;

import java.util.concurrent.CompletableFuture;

/**
 * Loads a Binance order-book snapshot off the consumer thread.
 *
 * <p>The returned future is completed by a background fetcher (which owns retries/backoff and the
 * resilience policies). The implementation must <b>not</b> touch any {@code SymbolState}: all state
 * mutation stays on the single consumer thread that drains the future, preserving the per-symbol
 * sequential-processing invariant.
 */
public interface AsyncSnapshotPort {

    CompletableFuture<OrderBookSnapshot> fetch(String symbol, int depthLimit);
}
