package com.trading.mss.message.outbound;

public record ProjectedPriceLevel(
        String price,
        String qty
) {}
