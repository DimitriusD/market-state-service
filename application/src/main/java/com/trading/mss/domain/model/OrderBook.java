package com.trading.mss.domain.model;

import java.util.Comparator;
import java.util.NavigableMap;
import java.util.TreeMap;

public class OrderBook {

    private final NavigableMap<Long, Long> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Long> asks = new TreeMap<>();

    public NavigableMap<Long, Long> getBids() {
        return bids;
    }

    public NavigableMap<Long, Long> getAsks() {
        return asks;
    }

    public Long bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    public Long bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    public boolean hasBids() {
        return !bids.isEmpty();
    }

    public boolean hasAsks() {
        return !asks.isEmpty();
    }

    public void clear() {
        bids.clear();
        asks.clear();
    }
}
