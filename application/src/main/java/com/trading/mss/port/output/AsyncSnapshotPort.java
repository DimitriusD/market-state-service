package com.trading.mss.port.output;

import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.OrderBookSnapshot;

import java.util.concurrent.CompletableFuture;

public interface AsyncSnapshotPort {

    /**
     * Fetches a snapshot for the given instrument. Takes the full {@link InstrumentKey} — not just the
     * native symbol — so the routing attributes ({@code exchange}, {@code marketType}) travel with the
     * request and a future multi-exchange implementation can select the correct endpoint/adapter.
     */
    CompletableFuture<OrderBookSnapshot> fetch(InstrumentKey key, int depthLimit);
}
