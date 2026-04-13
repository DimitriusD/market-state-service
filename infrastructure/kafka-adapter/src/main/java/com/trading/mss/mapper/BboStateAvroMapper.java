package com.trading.mss.mapper;

import com.trading.contracts.common.EventMetadata;
import com.trading.contracts.market.PriceLevel;
import com.trading.contracts.orderbook.BboStateEvent;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.orderbook.BboStateDto;

public final class BboStateAvroMapper {

    private BboStateAvroMapper() {}

    public static BboStateEvent toAvro(BboStateDto dto) {
        MetadataDto m = dto.metadata();
        EventMetadata metadata = new EventMetadata(
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

        PriceLevel bestBid = new PriceLevel(dto.bestBid().price(), dto.bestBid().qty());
        PriceLevel bestAsk = new PriceLevel(dto.bestAsk().price(), dto.bestAsk().qty());

        return new BboStateEvent(
                metadata,
                bestBid,
                bestAsk,
                dto.spread(),
                dto.mid(),
                dto.syncStatus());
    }
}
