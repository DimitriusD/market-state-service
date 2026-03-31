package com.trading.mss.message.outbound;

public record BboStateEvent(
        BboMetadata metadata,
        ProjectedPriceLevel bestBid,
        ProjectedPriceLevel bestAsk,
        String spread,
        String mid,
        boolean trusted
) {}
