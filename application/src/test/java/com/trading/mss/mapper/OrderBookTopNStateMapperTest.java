package com.trading.mss.mapper;

import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.message.outbound.OrderBookTopNStateEvent;
import com.trading.mss.message.outbound.ProjectedPriceLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTopNStateMapperTest {

    private OrderBookTopNStateMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OrderBookTopNStateMapper();
    }

    @Test
    void bidsInDescendingOrder() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("49999.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("2.0"));
        state.getOrderBook().getBids().put(ScaledDecimal.parse("49998.00"), ScaledDecimal.parse("3.0"));

        OrderBookTopNStateEvent event = mapper.project(state, 10);

        List<ProjectedPriceLevel> bids = event.bids();
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

        OrderBookTopNStateEvent event = mapper.project(state, 10);

        List<ProjectedPriceLevel> asks = event.asks();
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

        OrderBookTopNStateEvent event = mapper.project(state, 5);

        assertEquals(5, event.bids().size());
        assertEquals(5, event.asks().size());
        assertEquals("50000.00000000", event.bids().get(0).price());
        assertEquals("49999.00000000", event.bids().get(1).price());
        assertEquals("50001.00000000", event.asks().get(0).price());
        assertEquals("50002.00000000", event.asks().get(1).price());
    }

    @Test
    void fewerLevelsThanDepth_takesAll() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.0"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("1.0"));

        OrderBookTopNStateEvent event = mapper.project(state, 10);

        assertEquals(1, event.bids().size());
        assertEquals(1, event.asks().size());
        assertEquals(10, event.depth());
    }

    @Test
    void emptyBookReturnsEmptyLists() {
        SymbolState state = liveState();

        OrderBookTopNStateEvent event = mapper.project(state, 5);

        assertTrue(event.bids().isEmpty());
        assertTrue(event.asks().isEmpty());
        assertEquals(5, event.depth());
    }

    @Test
    void formattingDecimalStringsCorrectly() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("59827.95000000"), ScaledDecimal.parse("0.00551000"));

        OrderBookTopNStateEvent event = mapper.project(state, 5);

        assertEquals("59827.95000000", event.bids().get(0).price());
        assertEquals("0.00551000", event.bids().get(0).qty());
    }

    @Test
    void metadataIsPopulatedFromState() {
        SymbolState state = liveState();
        state.setLastEventExchangeTs(1000L);
        state.setLastEventProcessedTs(2000L);
        state.setLocalUpdateId(42);

        OrderBookTopNStateEvent event = mapper.project(state, 10);

        assertEquals(1, event.metadata().schemaVersion());
        assertEquals("orderbook_l2_topn_state", event.metadata().eventType());
        assertEquals("binance", event.metadata().exchange());
        assertEquals("spot", event.metadata().marketType());
        assertEquals("BTCUSDT", event.metadata().symbol());
        assertEquals("BTCUSDT", event.metadata().instrumentId());
        assertEquals(1000L, event.metadata().exchangeTs());
        assertEquals(2000L, event.metadata().processedTs());
        assertEquals(42, event.metadata().localUpdateId());
    }

    @Test
    void trustedFlagIsPreserved() {
        SymbolState state = liveState();
        state.setTrusted(true);

        OrderBookTopNStateEvent event = mapper.project(state, 5);

        assertTrue(event.trusted());
    }

    @Test
    void qtyValuesAreProjected() {
        SymbolState state = liveState();
        state.getOrderBook().getBids().put(ScaledDecimal.parse("50000.00"), ScaledDecimal.parse("1.50000000"));
        state.getOrderBook().getAsks().put(ScaledDecimal.parse("50001.00"), ScaledDecimal.parse("2.75000000"));

        OrderBookTopNStateEvent event = mapper.project(state, 5);

        assertEquals("1.50000000", event.bids().get(0).qty());
        assertEquals("2.75000000", event.asks().get(0).qty());
    }

    private static SymbolState liveState() {
        SymbolState state = new SymbolState("BTCUSDT", "binance");
        state.setStatus(SymbolStateStatus.LIVE);
        state.setTrusted(true);
        state.setMarketType("spot");
        state.setInstrumentId("BTCUSDT");
        return state;
    }
}
