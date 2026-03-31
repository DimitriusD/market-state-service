package com.trading.mss.mapper;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.message.outbound.OrderBookStateMetadata;
import com.trading.mss.message.outbound.OrderBookTopNStateEvent;
import com.trading.mss.message.outbound.ProjectedPriceLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

public class OrderBookTopNStateMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final String EVENT_TYPE = "orderbook_l2_topn_state";

    public OrderBookTopNStateEvent project(SymbolState state, int depth) {
        OrderBook book = state.getOrderBook();

        List<ProjectedPriceLevel> bids = projectLevels(book.getBids(), depth);
        List<ProjectedPriceLevel> asks = projectLevels(book.getAsks(), depth);

        OrderBookStateMetadata metadata = new OrderBookStateMetadata(
                SCHEMA_VERSION,
                EVENT_TYPE,
                state.getVenue(),
                state.getMarketType(),
                state.getSymbol(),
                state.getInstrumentId(),
                state.getLastEventExchangeTs(),
                state.getLastEventProcessedTs(),
                state.getLocalUpdateId());

        return new OrderBookTopNStateEvent(metadata, bids, asks, depth, state.isTrusted());
    }

    private List<ProjectedPriceLevel> projectLevels(NavigableMap<Long, Long> side, int depth) {
        List<ProjectedPriceLevel> result = new ArrayList<>(Math.min(depth, side.size()));
        int count = 0;
        for (Map.Entry<Long, Long> entry : side.entrySet()) {
            if (count >= depth) break;
            result.add(new ProjectedPriceLevel(
                    ScaledDecimal.format(entry.getKey()),
                    ScaledDecimal.format(entry.getValue())));
            count++;
        }
        return result;
    }
}
