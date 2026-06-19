package com.trading.mss.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    @Test
    void notCrossedWhenBidBelowAsk() {
        OrderBook book = new OrderBook();
        book.getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        book.getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));

        assertFalse(book.isCrossed());
    }

    @Test
    void notCrossedWhenLocked() {
        OrderBook book = new OrderBook();
        book.getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        book.getAsks().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));

        assertFalse(book.isCrossed());
    }

    @Test
    void crossedWhenBidAboveAsk() {
        OrderBook book = new OrderBook();
        book.getBids().put(ScaledDecimal.parse("50002.00"), ScaledDecimal.parse("1.0"));
        book.getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));

        assertTrue(book.isCrossed());
    }

    @Test
    void notCrossedWhenOneSideEmpty() {
        OrderBook onlyBids = new OrderBook();
        onlyBids.getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        assertFalse(onlyBids.isCrossed());

        OrderBook onlyAsks = new OrderBook();
        onlyAsks.getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));
        assertFalse(onlyAsks.isCrossed());

        assertFalse(new OrderBook().isCrossed());
    }
}
