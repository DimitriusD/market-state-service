package com.trading.mss.message.inbound;

public record PriceLevel(
        String price,
        String qty
) {}
