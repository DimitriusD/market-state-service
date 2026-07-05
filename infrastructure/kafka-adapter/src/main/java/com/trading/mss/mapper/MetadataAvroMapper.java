package com.trading.mss.mapper;

import com.trading.contracts.common.MetadataEvent;
import com.trading.mss.dto.common.MetadataDto;

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
}
