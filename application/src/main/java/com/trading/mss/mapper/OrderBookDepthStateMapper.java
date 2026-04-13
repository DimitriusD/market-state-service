package com.trading.mss.mapper;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.orderbook.BookSyncStatus;
import com.trading.mss.dto.orderbook.OrderBookDepthStateDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

public class OrderBookDepthStateMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final String EVENT_TYPE = "orderbook_l2_depth_state";

    public OrderBookDepthStateDto project(SymbolState state, int publishedLevels) {
        OrderBook book = state.getOrderBook();

        List<PriceLevelDto> bidLevels = projectLevels(book.getBids(), publishedLevels);
        List<PriceLevelDto> askLevels = projectLevels(book.getAsks(), publishedLevels);

        MetadataDto metadata = StateEventMetadataFactory.from(state, SCHEMA_VERSION, EVENT_TYPE);

        return new OrderBookDepthStateDto(
                metadata,
                publishedLevels,
                bidLevels,
                askLevels,
                state.isTrusted() ? BookSyncStatus.IN_SYNC : BookSyncStatus.OUT_OF_SYNC);
    }

    private List<PriceLevelDto> projectLevels(NavigableMap<Long, Long> side, int publishedLevels) {
        List<PriceLevelDto> result = new ArrayList<>(Math.min(publishedLevels, side.size()));
        int count = 0;
        for (Map.Entry<Long, Long> entry : side.entrySet()) {
            if (count >= publishedLevels) break;
            result.add(new PriceLevelDto(
                    ScaledDecimal.format(entry.getKey()),
                    ScaledDecimal.format(entry.getValue())));
            count++;
        }
        return result;
    }
}
