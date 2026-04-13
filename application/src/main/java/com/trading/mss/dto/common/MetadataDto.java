package com.trading.mss.dto.common;

public record MetadataDto(
        int schemaVersion,
        String eventType,
        String exchange,
        String marketType,
        String base,
        String quote,
        String symbol,
        String instrumentId,
        String eventId,
        String sourceStream,
        long exchangeTs,
        long receivedTs,
        long processedTs
) {}
