package com.trading.mss.port.output;

import com.trading.mss.domain.model.OrderBookSnapshot;

public interface BinanceSpotSnapshotApiService {

    OrderBookSnapshot load(String symbol, int depthLimit);
}
