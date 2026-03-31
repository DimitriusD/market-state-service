package com.trading.mss.message.outbound;

import java.util.List;

public record OrderBookTopNStateEvent(
        OrderBookStateMetadata metadata,
        List<ProjectedPriceLevel> bids,
        List<ProjectedPriceLevel> asks,
        int depth,
        boolean trusted
) {}
