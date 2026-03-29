package com.trading.mss.message.inbound;

import java.util.List;

public record DepthDiffEvent(
        Metadata metadata,
        Long transactionTs,
        long firstUpdateId,
        long finalUpdateId,
        Long previousFinalUpdateId,
        List<PriceLevel> bids,
        List<PriceLevel> asks
) {}
