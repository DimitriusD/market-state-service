package com.trading.mss.message.inbound;

public record Metadata(
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
