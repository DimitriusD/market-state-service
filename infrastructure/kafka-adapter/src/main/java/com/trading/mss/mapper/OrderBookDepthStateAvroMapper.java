package com.trading.mss.mapper;

import com.trading.contracts.common.MetadataEvent;
import com.trading.contracts.common.PriceLevelEvent;
import com.trading.contracts.orderbook.OrderBookDepthStateEvent;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.orderbook.OrderBookDepthStateDto;

import java.util.List;

public final class OrderBookDepthStateAvroMapper {

    private OrderBookDepthStateAvroMapper() {}

    public static OrderBookDepthStateEvent toAvro(OrderBookDepthStateDto dto) {
        MetadataDto m = dto.metadata();
        MetadataEvent metadata = new MetadataEvent(
                m.schemaVersion(),
                m.eventType(),
                m.exchange(),
                m.marketType(),
                m.base(),
                m.quote(),
                m.symbol(),
                m.instrumentId(),
                m.eventId(),
                m.sourceStream(),
                m.exchangeTs(),
                m.receivedTs(),
                m.processedTs());

        List<PriceLevelEvent> bidLevels =
                dto.bidLevels().stream().map(pl -> new PriceLevelEvent(pl.price(), pl.qty())).toList();
        List<PriceLevelEvent> askLevels =
                dto.askLevels().stream().map(pl -> new PriceLevelEvent(pl.price(), pl.qty())).toList();

        return new OrderBookDepthStateEvent(
                metadata,
                dto.publishedLevels(),
                bidLevels,
                askLevels,
                BookSyncStatusAvroMapper.toWire(dto.syncStatus()));
    }
}
