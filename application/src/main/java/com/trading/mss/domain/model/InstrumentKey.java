package com.trading.mss.domain.model;

import com.trading.mss.dto.common.MetadataDto;

public record InstrumentKey(
        String instrumentId,
        String exchange,
        String marketType,
        String symbol
) {
    public static InstrumentKey of(MetadataDto metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        return new InstrumentKey(
                metadata.instrumentId(),
                metadata.exchange(),
                metadata.marketType(),
                metadata.symbol()
        );
    }

    public String canonical() {
        return instrumentId;
    }
}
