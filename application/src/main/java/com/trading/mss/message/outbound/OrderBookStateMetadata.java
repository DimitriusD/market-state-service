package com.trading.mss.message.outbound;

public record OrderBookStateMetadata(
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
