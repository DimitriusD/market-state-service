package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.common.PriceLevelDto;

import java.util.List;
import java.util.NavigableMap;

public class OrderBookApplier {

    public void applyDiff(OrderBook orderBook, DepthDiffDto event) {
        var bids = event.bids() != null ? event.bids() : List.<PriceLevelDto>of();
        var asks = event.asks() != null ? event.asks() : List.<PriceLevelDto>of();

        applyLevels(orderBook.getBids(), bids);
        applyLevels(orderBook.getAsks(), asks);
    }

    public void applySnapshot(OrderBook orderBook, OrderBookSnapshot snapshot) {
        orderBook.clear();
        applyLevels(orderBook.getBids(), snapshot.bids() != null ? snapshot.bids() : List.of());
        applyLevels(orderBook.getAsks(), snapshot.asks() != null ? snapshot.asks() : List.of());
    }

    private void applyLevels(NavigableMap<Long, Long> side, List<PriceLevelDto> levels) {
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
