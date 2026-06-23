package com.trading.mss.mapper;

import com.trading.contracts.common.MetadataEvent;
import com.trading.contracts.common.PriceLevelEvent;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;

public final class MetadataAvroMapper {

    private MetadataAvroMapper() {}

    public static MetadataEvent toAvro(MetadataDto m) {
        return new MetadataEvent(
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
    }

    public static PriceLevelEvent toAvro(PriceLevelDto pl) {
        if (pl == null) {
            return null;
        }
        return new PriceLevelEvent(pl.price(), pl.qty());
    }
}
