package com.trading.mss.mapper;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookL2SnapshotMapperTest {

    private static final int PUBLISHED_DEPTH = 10;
    private static final int SNAPSHOT_DEPTH_LIMIT = 1000;
    private static final long NOW = 1_700_000_500_000L;

    private OrderBookL2SnapshotMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OrderBookL2SnapshotMapper(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void liveTrustedValidBook_producesSnapshotWithBboAndDepth() {
        SymbolState state = liveState();
        putBids(state, "50000.00", "1.5", "49999.00", "2.0");
        putAsks(state, "50001.00", "0.75", "50002.00", "3.0");

        Optional<OrderBookL2SnapshotDto> result = mapper.project(state, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT);

        assertTrue(result.isPresent());
        OrderBookL2SnapshotDto snap = result.get();
        assertNotNull(snap.bbo());
        assertNotNull(snap.depth());

        // bbo + depth come from the same reconstructed version
        assertEquals(105, snap.version().exchangeUpdateId());
        assertEquals("50000.00000000", snap.bbo().bestBid().price());
        assertEquals("50001.00000000", snap.bbo().bestAsk().price());

        // bids descending, asks ascending
        assertEquals("50000.00000000", snap.depth().bids().get(0).price());
        assertEquals("49999.00000000", snap.depth().bids().get(1).price());
        assertEquals("50001.00000000", snap.depth().asks().get(0).price());
        assertEquals("50002.00000000", snap.depth().asks().get(1).price());

        // spread & mid
        assertEquals("1.00000000", snap.bbo().spread());
        assertEquals("50000.50000000", snap.bbo().mid());

        // quality
        assertEquals(SymbolStateStatus.LIVE, snap.quality().lifecycleStatus());
        assertEquals(BookSyncStatus.IN_SYNC, snap.quality().syncStatus());
        assertTrue(snap.quality().trusted());
        assertEquals(OrderBookReason.NONE, snap.quality().reason());
        assertFalse(snap.quality().crossed());
        assertFalse(snap.quality().incompleteBook());

        // version
        assertEquals(100, snap.version().lastSnapshotUpdateId());
        assertEquals(SNAPSHOT_DEPTH_LIMIT, snap.version().snapshotDepthLimit());
        assertEquals(PUBLISHED_DEPTH, snap.version().publishedDepth());

        // source
        assertEquals("canonical.market.depthdiff.v1", snap.source().inputTopic());
        assertEquals("BTCUSDT", snap.source().inputKey());
        assertEquals(3, snap.source().inputPartition());
        assertEquals(42, snap.source().inputOffset());
        assertEquals("evt-123", snap.source().upstreamEventId());
        assertEquals(106L, snap.source().inputFirstUpdateId());
        assertEquals(110L, snap.source().inputFinalUpdateId());

        // metadata
        assertEquals("ORDERBOOK_L2_SNAPSHOT", snap.metadata().eventType());
        assertEquals("market-state-service", snap.metadata().sourceStream());
        assertEquals(NOW, snap.metadata().processedTs());
    }

    @Test
    void stateSeqIncrementsOnEachPublishedSnapshot() {
        SymbolState state = liveState();
        putBids(state, "50000.00", "1.0");
        putAsks(state, "50001.00", "1.0");

        long first = mapper.project(state, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).orElseThrow().version().stateSeq();
        long second = mapper.project(state, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).orElseThrow().version().stateSeq();

        assertEquals(1, first);
        assertEquals(2, second);
    }

    @Test
    void emptyResultDoesNotConsumeStateSeq() {
        SymbolState crossed = liveState();
        putBids(crossed, "50002.00", "1.0");
        putAsks(crossed, "50000.00", "1.0");

        assertTrue(mapper.project(crossed, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).isEmpty());
        assertEquals(0, crossed.getStateSeq());
    }

    @Test
    void crossedBook_returnsEmpty() {
        SymbolState state = liveState();
        putBids(state, "50002.00", "1.0");
        putAsks(state, "50000.00", "1.0");

        assertTrue(mapper.project(state, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).isEmpty());
    }

    @Test
    void oneSidedBook_returnsEmpty() {
        SymbolState onlyBids = liveState();
        putBids(onlyBids, "50000.00", "1.0");
        assertTrue(mapper.project(onlyBids, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).isEmpty());

        SymbolState onlyAsks = liveState();
        putAsks(onlyAsks, "50001.00", "1.0");
        assertTrue(mapper.project(onlyAsks, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).isEmpty());
    }

    @Test
    void notLiveOrNotTrusted_returnsEmpty() {
        SymbolState notLive = liveState();
        notLive.setStatus(SymbolStateStatus.RESYNCING);
        putBids(notLive, "50000.00", "1.0");
        putAsks(notLive, "50001.00", "1.0");
        assertTrue(mapper.project(notLive, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).isEmpty());

        SymbolState notTrusted = liveState();
        notTrusted.setTrusted(false);
        putBids(notTrusted, "50000.00", "1.0");
        putAsks(notTrusted, "50001.00", "1.0");
        assertTrue(mapper.project(notTrusted, event(), ctx(), PUBLISHED_DEPTH, SNAPSHOT_DEPTH_LIMIT).isEmpty());
    }

    @Test
    void respectsPublishedDepth() {
        SymbolState state = liveState();
        putBids(state, "50000.00", "1.0", "49999.00", "1.0", "49998.00", "1.0");
        putAsks(state, "50001.00", "1.0", "50002.00", "1.0", "50003.00", "1.0");

        OrderBookL2SnapshotDto snap =
                mapper.project(state, event(), ctx(), 2, SNAPSHOT_DEPTH_LIMIT).orElseThrow();

        assertEquals(2, snap.depth().bids().size());
        assertEquals(2, snap.depth().asks().size());
    }

    private static SymbolState liveState() {
        SymbolState state = new SymbolState(new InstrumentKey("BINANCE|SPOT|BTC|USDT", "binance", "spot", "BTCUSDT"));
        state.setStatus(SymbolStateStatus.LIVE);
        state.setTrusted(true);
        state.setBase("BTC");
        state.setQuote("USDT");
        state.setLocalUpdateId(105);
        state.setLastSnapshotUpdateId(100);
        state.setLastEventExchangeTs(NOW - 50);
        state.setLastEventReceivedTs(NOW - 40);
        return state;
    }

    private static void putBids(SymbolState state, String... priceQtyPairs) {
        for (int i = 0; i < priceQtyPairs.length; i += 2) {
            state.getOrderBook().getBids().put(
                    ScaledDecimal.parse(priceQtyPairs[i]), ScaledDecimal.parse(priceQtyPairs[i + 1]));
        }
    }

    private static void putAsks(SymbolState state, String... priceQtyPairs) {
        for (int i = 0; i < priceQtyPairs.length; i += 2) {
            state.getOrderBook().getAsks().put(
                    ScaledDecimal.parse(priceQtyPairs[i]), ScaledDecimal.parse(priceQtyPairs[i + 1]));
        }
    }

    private static DepthDiffDto event() {
        var metadata = new MetadataDto(1, "depthDiff", "binance", "spot",
                "BTC", "USDT", "BTCUSDT", "BTCUSDT", "evt-123", "stream-1",
                NOW - 50, NOW - 40, NOW - 30);
        return new DepthDiffDto(metadata, 106, 110, 104L, List.of(), List.of());
    }

    private static KafkaMessageContext ctx() {
        return new KafkaMessageContext("canonical.market.depthdiff.v1", "BTCUSDT", 3, 42);
    }
}
