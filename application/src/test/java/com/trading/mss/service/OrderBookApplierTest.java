package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookApplierTest {

    private OrderBookApplier applier;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        applier = new OrderBookApplier();
        book = new OrderBook();
    }

    @Test
    void addsNewBidLevel() {
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "1.5")),
                List.of()
        ));

        assertEquals(1, book.getBids().size());
        assertEquals(ScaledDecimal.parse("1.5"), book.getBids().get(ScaledDecimal.parse("100.00")));
    }

    @Test
    void updatesExistingBidLevel() {
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "1.0")),
                List.of()
        ));
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "2.5")),
                List.of()
        ));

        assertEquals(1, book.getBids().size());
        assertEquals(ScaledDecimal.parse("2.5"), book.getBids().get(ScaledDecimal.parse("100.00")));
    }

    @Test
    void removesLevelWhenQtyIsZero() {
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "1.0")),
                List.of()
        ));
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "0")),
                List.of()
        ));

        assertTrue(book.getBids().isEmpty());
    }

    @Test
    void addsNewAskLevel() {
        applier.applyDiff(book, diff(
                List.of(),
                List.of(new PriceLevelDto("200.00", "3.0"))
        ));

        assertEquals(1, book.getAsks().size());
        assertEquals(ScaledDecimal.parse("3.0"), book.getAsks().get(ScaledDecimal.parse("200.00")));
    }

    @Test
    void bestBidReturnsHighestPrice() {
        applier.applyDiff(book, diff(
                List.of(
                        new PriceLevelDto("100.00", "1.0"),
                        new PriceLevelDto("105.00", "2.0"),
                        new PriceLevelDto("99.00", "3.0")
                ),
                List.of()
        ));

        assertEquals(ScaledDecimal.parse("105.00"), book.bestBid());
    }

    @Test
    void bestAskReturnsLowestPrice() {
        applier.applyDiff(book, diff(
                List.of(),
                List.of(
                        new PriceLevelDto("200.00", "1.0"),
                        new PriceLevelDto("195.00", "2.0"),
                        new PriceLevelDto("210.00", "3.0")
                )
        ));

        assertEquals(ScaledDecimal.parse("195.00"), book.bestAsk());
    }

    @Test
    void bidsAreInDescendingOrder() {
        applier.applyDiff(book, diff(
                List.of(
                        new PriceLevelDto("100.00", "1.0"),
                        new PriceLevelDto("102.00", "1.0"),
                        new PriceLevelDto("101.00", "1.0")
                ),
                List.of()
        ));

        var prices = new ArrayList<>(book.getBids().keySet());
        assertEquals(ScaledDecimal.parse("102.00"), prices.get(0));
        assertEquals(ScaledDecimal.parse("101.00"), prices.get(1));
        assertEquals(ScaledDecimal.parse("100.00"), prices.get(2));
    }

    @Test
    void asksAreInAscendingOrder() {
        applier.applyDiff(book, diff(
                List.of(),
                List.of(
                        new PriceLevelDto("203.00", "1.0"),
                        new PriceLevelDto("201.00", "1.0"),
                        new PriceLevelDto("202.00", "1.0")
                )
        ));

        var prices = new ArrayList<>(book.getAsks().keySet());
        assertEquals(ScaledDecimal.parse("201.00"), prices.get(0));
        assertEquals(ScaledDecimal.parse("202.00"), prices.get(1));
        assertEquals(ScaledDecimal.parse("203.00"), prices.get(2));
    }

    @Test
    void bestBidReturnsNullWhenEmpty() {
        assertNull(book.bestBid());
    }

    @Test
    void bestAskReturnsNullWhenEmpty() {
        assertNull(book.bestAsk());
    }

    @Test
    void handlesNullBidsAndAsks() {
        var event = new DepthDiffDto(dummyMetadata(), null, 1, 2, null, null, null);
        applier.applyDiff(book, event);

        assertTrue(book.getBids().isEmpty());
        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    void clearRemovesAllLevels() {
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "1.0")),
                List.of(new PriceLevelDto("200.00", "2.0"))
        ));

        book.clear();

        assertTrue(book.getBids().isEmpty());
        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    void applySnapshot_clearsBookAndAppliesLevels() {
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "1.0")),
                List.of(new PriceLevelDto("200.00", "2.0"))
        ));

        var snapshot = new OrderBookSnapshot(
                "BTCUSDT", "binance", 500,
                List.of(new PriceLevelDto("50000.00", "1.5"), new PriceLevelDto("49999.00", "2.0")),
                List.of(new PriceLevelDto("50001.00", "1.0"), new PriceLevelDto("50002.00", "3.0")),
                1000, System.currentTimeMillis()
        );

        applier.applySnapshot(book, snapshot);

        assertEquals(2, book.getBids().size());
        assertEquals(2, book.getAsks().size());
        assertEquals(ScaledDecimal.parse("50000.00"), book.bestBid());
        assertEquals(ScaledDecimal.parse("50001.00"), book.bestAsk());
    }

    @Test
    void applySnapshot_handlesNullLists() {
        var snapshot = new OrderBookSnapshot("BTCUSDT", "binance", 500,
                null, null, 1000, System.currentTimeMillis());

        applier.applySnapshot(book, snapshot);

        assertTrue(book.getBids().isEmpty());
        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    void applySnapshot_replacesExistingBook() {
        applier.applyDiff(book, diff(
                List.of(new PriceLevelDto("100.00", "1.0"), new PriceLevelDto("101.00", "2.0")),
                List.of(new PriceLevelDto("200.00", "3.0"))
        ));
        assertEquals(2, book.getBids().size());

        var snapshot = new OrderBookSnapshot("BTCUSDT", "binance", 500,
                List.of(new PriceLevelDto("50000.00", "1.0")),
                List.of(new PriceLevelDto("50001.00", "1.0")),
                1000, System.currentTimeMillis());

        applier.applySnapshot(book, snapshot);

        assertEquals(1, book.getBids().size());
        assertEquals(1, book.getAsks().size());
        assertNull(book.getBids().get(ScaledDecimal.parse("100.00")));
    }

    private static DepthDiffDto diff(List<PriceLevelDto> bids, List<PriceLevelDto> asks) {
        return new DepthDiffDto(dummyMetadata(), null, 1, 2, null, bids, asks);
    }

    private static MetadataDto dummyMetadata() {
        return new MetadataDto(1, "depth_diff", "binance", "spot",
                "BTC", "USDT", "BTCUSDT", "BINANCE|SPOT|BTC|USDT",
                "test-event-id", "btcusdt@depth@100ms", 0, 0, 0);
    }
}
