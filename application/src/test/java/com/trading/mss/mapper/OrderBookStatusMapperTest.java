package com.trading.mss.mapper;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.orderbook.OrderBookStatusDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookStatusMapperTest {

    private static final long NOW = 1_700_000_500_000L;

    private OrderBookStatusMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OrderBookStatusMapper(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void liveStatus_isInSyncAndTrusted() {
        SymbolState state = baseState();
        state.setStatus(SymbolStateStatus.LIVE);
        state.setTrusted(true);

        OrderBookStatusDto status = mapper.project(state, OrderBookReason.NONE, null, null, "Order book is live");

        assertEquals(SymbolStateStatus.LIVE, status.lifecycleStatus());
        assertEquals(BookSyncStatus.IN_SYNC, status.syncStatus());
        assertTrue(status.trusted());
        assertEquals(OrderBookReason.NONE, status.reason());
        assertEquals("ORDERBOOK_STATUS", status.metadata().eventType());
        assertEquals("Order book is live", status.message());
    }

    @Test
    void crossedBookResync_isOutOfSyncWithReason() {
        SymbolState state = baseState();
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.setTrusted(false);
        // diagnostic crossed book
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50002.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));

        OrderBookStatusDto status = mapper.project(state, OrderBookReason.CROSSED_BOOK, null, null, "crossed");

        assertEquals(SymbolStateStatus.RESYNCING, status.lifecycleStatus());
        assertEquals(BookSyncStatus.OUT_OF_SYNC, status.syncStatus());
        assertFalse(status.trusted());
        assertEquals(OrderBookReason.CROSSED_BOOK, status.reason());
        // diagnostic best bid/ask still reported
        assertNotNull(status.details().bestBid());
        assertNotNull(status.details().bestAsk());
        assertEquals("50002.00000000", status.details().bestBid().price());
    }

    @Test
    void bufferingStatus_isRecovering() {
        SymbolState state = baseState();
        state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);

        OrderBookStatusDto status = mapper.project(state, OrderBookReason.NONE, null, null, "buffering");

        assertEquals(BookSyncStatus.RECOVERING, status.syncStatus());
    }

    @Test
    void detailsCarryCountersAndUpdateIds() {
        SymbolState state = baseState();
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.setLocalUpdateId(105);
        state.setLastSnapshotUpdateId(100);
        state.incrementGapCount();
        state.incrementResyncCount();

        OrderBookStatusDto status = mapper.project(state, OrderBookReason.GAP_DETECTED, null, null, "gap");

        assertEquals(105, status.details().localUpdateId());
        assertEquals(100, status.details().lastSnapshotUpdateId());
        assertEquals(1, status.details().gapCount());
        assertEquals(1, status.details().resyncCount());
    }

    @Test
    void usesRecordedInputContextWhenCtxNull() {
        SymbolState state = baseState();
        state.setStatus(SymbolStateStatus.RESYNCING);
        state.recordInputContext(null,
                new KafkaMessageContext("canonical.market.depthdiff.v1", "BTCUSDT", 7, 99));

        OrderBookStatusDto status = mapper.project(state, OrderBookReason.GAP_DETECTED, null, null, "gap");

        assertEquals("canonical.market.depthdiff.v1", status.details().inputTopic());
        assertEquals(7, status.details().inputPartition());
        assertEquals(99, status.details().inputOffset());
    }

    private static SymbolState baseState() {
        SymbolState state = new SymbolState("BTCUSDT", "binance");
        state.setMarketType("spot");
        state.setBase("BTC");
        state.setQuote("USDT");
        state.setInstrumentId("BTCUSDT");
        return state;
    }
}
