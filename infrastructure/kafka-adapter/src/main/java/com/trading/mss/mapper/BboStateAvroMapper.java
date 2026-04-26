package com.trading.mss.mapper;

import com.trading.contracts.common.MetadataEvent;
import com.trading.contracts.common.PriceLevelEvent;
import com.trading.contracts.orderbook.BboStateEvent;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.orderbook.BboStateDto;

public final class BboStateAvroMapper {

    private BboStateAvroMapper() {}

    public static BboStateEvent toAvro(BboStateDto dto) {
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

        PriceLevelEvent bestBid = new PriceLevelEvent(dto.bestBid().price(), dto.bestBid().qty());
        PriceLevelEvent bestAsk = new PriceLevelEvent(dto.bestAsk().price(), dto.bestAsk().qty());

        return new BboStateEvent(
                metadata,
                bestBid,
                bestAsk,
                dto.spread(),
                dto.mid(),
                BookSyncStatusAvroMapper.toWire(dto.syncStatus()));
    }
}
