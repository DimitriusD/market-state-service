package com.trading.mss.message.outbound;

public record BboMetadata(
        int schemaVersion,
        String eventType,
        String exchange,
        String marketType,
        String symbol,
        String instrumentId,
        long exchangeTs,
        long processedTs,
        long localUpdateId
) {}
