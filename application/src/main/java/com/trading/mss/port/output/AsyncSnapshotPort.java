package com.trading.mss.port.output;

import com.trading.mss.domain.model.OrderBookSnapshot;

import java.util.concurrent.CompletableFuture;

public interface AsyncSnapshotPort {

    CompletableFuture<OrderBookSnapshot> fetch(String symbol, int depthLimit);
}
