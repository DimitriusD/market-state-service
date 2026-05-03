package com.trading.mss.mapper;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.orderbook.BboStateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BboStateMapperTest {

    private BboStateMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new BboStateMapper();
    }

    @Test
    void projectsBestBidAndBestAsk() {
        SymbolState state = liveState();
        OrderBook book = state.getOrderBook();
        book.getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.50000000"));
        book.getBids().put(ScaledDecimal.parse("49999.00"), ScaledDecimal.parse("2.00000000"));
        book.getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("0.75000000"));
        book.getAsks().put(ScaledDecimal.parse("50002.00"), ScaledDecimal.parse("1.00000000"));

        Optional<BboStateDto> result = mapper.project(state);

        assertTrue(result.isPresent());
        BboStateDto event = result.get();
        assertEquals("50000.00000000", event.bestBid().price());
        assertEquals("1.50000000", event.bestBid().qty());
        assertEquals("50001.00000000", event.bestAsk().price());
        assertEquals("0.75000000", event.bestAsk().qty());
    }

    @Test
    void calculatesSpreadCorrectly() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));

        BboStateDto event = mapper.project(state).orElseThrow();

        assertEquals("1.00000000", event.spread());
    }

    @Test
    void calculatesMidCorrectly() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50002.00"), ScaledDecimal.parse("1.0"));

        BboStateDto event = mapper.project(state).orElseThrow();

        assertEquals("50001.00000000", event.mid());
    }

    @Test
    void calculatesMidWithOddSum() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("1.00000001"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("1.00000002"), ScaledDecimal.parse("1.0"));

        BboStateDto event = mapper.project(state).orElseThrow();

        assertEquals("1.00000002", event.mid());
    }

    @Test
    void returnsEmptyWhenNoBids() {
        SymbolState state = liveState();
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));

        Optional<BboStateDto> result = mapper.project(state);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoAsks() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));

        Optional<BboStateDto> result = mapper.project(state);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenBookEmpty() {
        SymbolState state = liveState();

        Optional<BboStateDto> result = mapper.project(state);

        assertTrue(result.isEmpty());
    }

    @Test
    void throwsWhenBookCrossed() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50002.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));

        assertThrows(IllegalStateException.class, () -> mapper.project(state));
    }

    @Test
    void metadataIsPopulatedFromState() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));
        state.setLastEventExchangeTs(1000L);
        state.setLastEventProcessedTs(2000L);
        state.setLocalUpdateId(42);

        BboStateDto event = mapper.project(state).orElseThrow();

        assertEquals(1, event.metadata().schemaVersion());
        assertEquals("BBO_STATE", event.metadata().eventType());
        assertEquals("binance", event.metadata().exchange());
        assertEquals("spot", event.metadata().marketType());
        assertEquals("BTCUSDT", event.metadata().symbol());
        assertEquals("BTCUSDT", event.metadata().instrumentId());
        assertEquals(1000L, event.metadata().exchangeTs());
        assertEquals(0L, event.metadata().receivedTs());
        assertEquals(2000L, event.metadata().processedTs());
        assertEquals("42", event.metadata().eventId());
        assertEquals("mss", event.metadata().sourceStream());
        assertEquals("BTC", event.metadata().base());
        assertEquals("USDT", event.metadata().quote());
    }

    @Test
    void syncStatusInSyncWhenTrusted() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));
        state.setTrusted(true);

        BboStateDto event = mapper.project(state).orElseThrow();
        assertEquals(BookSyncStatus.IN_SYNC, event.syncStatus());
    }

    @Test
    void syncStatusOutOfSyncWhenUntrusted() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));
        state.setTrusted(false);

        BboStateDto event = mapper.project(state).orElseThrow();
        assertEquals(BookSyncStatus.OUT_OF_SYNC, event.syncStatus());
    }

    @Test
    void zeroSpreadWhenBidEqualsAsk() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));

        BboStateDto event = mapper.project(state).orElseThrow();

        assertEquals("0.00000000", event.spread());
        assertEquals("50000.00000000", event.mid());
    }

    private static SymbolState liveState() {
        SymbolState state = new SymbolState("BTCUSDT", "binance");
        state.setStatus(SymbolStateStatus.LIVE);
        state.setTrusted(true);
        state.setMarketType("spot");
        state.setBase("BTC");
        state.setQuote("USDT");
        state.setInstrumentId("BTCUSDT");
        return state;
    }
}
