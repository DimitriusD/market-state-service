package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.PriceLevel;

import java.util.List;
import java.util.NavigableMap;

public class OrderBookApplier {

    public void applyDiff(OrderBook orderBook, DepthDiffEvent event) {
        var bids = event.bids() != null ? event.bids() : List.<PriceLevel>of();
        var asks = event.asks() != null ? event.asks() : List.<PriceLevel>of();

        applyLevels(orderBook.getBids(), bids);
        applyLevels(orderBook.getAsks(), asks);
    }

    public void applySnapshot(OrderBook orderBook, OrderBookSnapshot snapshot) {
        orderBook.clear();
        applyLevels(orderBook.getBids(), snapshot.bids() != null ? snapshot.bids() : List.of());
        applyLevels(orderBook.getAsks(), snapshot.asks() != null ? snapshot.asks() : List.of());
    }

    private void applyLevels(NavigableMap<Long, Long> side, List<PriceLevel> levels) {
        for (var level : levels) {
            long price = ScaledDecimal.parse(level.price());
            long qty = ScaledDecimal.parse(level.qty());

            if (qty == 0) {
                side.remove(price);
            } else {
                side.put(price, qty);
            }
        }
    }
}
