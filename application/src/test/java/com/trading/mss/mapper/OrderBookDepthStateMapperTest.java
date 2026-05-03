package com.trading.mss.mapper;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.orderbook.OrderBookDepthStateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookDepthStateMapperTest {

    private OrderBookDepthStateMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OrderBookDepthStateMapper();
    }

    @Test
    void bidsInDescendingOrder() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("49999.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("2.0"));
        state.getOrderBook().getBids().put(ScaledDecimal.parse("49998.00"), ScaledDecimal.parse("3.0"));

        OrderBookDepthStateDto event = mapper.project(state, 10);

        List<PriceLevelDto> bids = event.bidLevels();
        assertEquals(3, bids.size());
        assertEquals("50000.00000000", bids.get(0).price());
        assertEquals("49999.00000000", bids.get(1).price());
        assertEquals("49998.00000000", bids.get(2).price());
    }

    @Test
    void asksInAscendingOrder() {
        SymbolState state = liveState();
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50002.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("2.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50003.00"), ScaledDecimal.parse("3.0"));

        OrderBookDepthStateDto event = mapper.project(state, 10);

        List<PriceLevelDto> asks = event.askLevels();
        assertEquals(3, asks.size());
        assertEquals("50001.00000000", asks.get(0).price());
        assertEquals("50002.00000000", asks.get(1).price());
        assertEquals("50003.00000000", asks.get(2).price());
    }

    @Test
    void takesOnlyTopNLevels() {
        SymbolState state = liveState();
        for (int i = 0; i < 20; i++) {
            state.getOrderBook().getBids().put(ScaledDecimal.parse(String.valueOf(50000 - i)), ScaledDecimal.parse("1.0"));
            state.getOrderBook().getAsks().put(ScaledDecimal.parse(String.valueOf(50001 + i)), ScaledDecimal.parse("1.0"));
        }

        OrderBookDepthStateDto event = mapper.project(state, 5);

        assertEquals(5, event.bidLevels().size());
        assertEquals(5, event.askLevels().size());
        assertEquals("50000.00000000", event.bidLevels().get(0).price());
        assertEquals("49999.00000000", event.bidLevels().get(1).price());
        assertEquals("50001.00000000", event.askLevels().get(0).price());
        assertEquals("50002.00000000", event.askLevels().get(1).price());
    }

    @Test
    void fewerLevelsThanPublishedCap_listsActualCount() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));

        OrderBookDepthStateDto event = mapper.project(state, 10);

        assertEquals(1, event.bidLevels().size());
        assertEquals(1, event.askLevels().size());
        assertEquals(10, event.publishedLevels());
    }

    @Test
    void emptyBookReturnsEmptyLists() {
        SymbolState state = liveState();

        OrderBookDepthStateDto event = mapper.project(state, 5);

        assertTrue(event.bidLevels().isEmpty());
        assertTrue(event.askLevels().isEmpty());
        assertEquals(5, event.publishedLevels());
    }

    @Test
    void formattingDecimalStringsCorrectly() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("59827.95000000"), ScaledDecimal.parse("0.00551000"));

        OrderBookDepthStateDto event = mapper.project(state, 5);

        assertEquals("59827.95000000", event.bidLevels().getFirst().price());
        assertEquals("0.00551000", event.bidLevels().getFirst().qty());
    }

    @Test
    void metadataIsPopulatedFromState() {
        SymbolState state = liveState();
        state.setLastEventExchangeTs(1000L);
        state.setLastEventProcessedTs(2000L);
        state.setLocalUpdateId(42);

        OrderBookDepthStateDto event = mapper.project(state, 10);

        assertEquals(1, event.metadata().schemaVersion());
        assertEquals("ORDERBOOK_DEPTH_STATE", event.metadata().eventType());
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
    void syncStatusReflectsTrustedFlag() {
        SymbolState state = liveState();
        state.setTrusted(true);

        OrderBookDepthStateDto event = mapper.project(state, 5);

        assertEquals(BookSyncStatus.IN_SYNC, event.syncStatus());
    }

    @Test
    void untrustedMapsToOutOfSync() {
        SymbolState state = liveState();
        state.setTrusted(false);

        OrderBookDepthStateDto event = mapper.project(state, 5);

        assertEquals(BookSyncStatus.OUT_OF_SYNC, event.syncStatus());
    }

    @Test
    void qtyValuesAreProjected() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.50000000"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("2.75000000"));

        OrderBookDepthStateDto event = mapper.project(state, 5);

        assertEquals("1.50000000", event.bidLevels().getFirst().qty());
        assertEquals("2.75000000", event.askLevels().getFirst().qty());
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
