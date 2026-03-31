package com.trading.mss.service;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.mapper.BboStateMapper;
import com.trading.mss.mapper.OrderBookTopNStateMapper;
import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.KafkaMessageContext;
import com.trading.mss.message.inbound.Metadata;
import com.trading.mss.message.inbound.PriceLevel;
import com.trading.mss.message.outbound.BboStateEvent;
import com.trading.mss.message.outbound.OrderBookTopNStateEvent;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookTopNStatePort;
import com.trading.mss.port.output.SymbolStateStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class ProcessDepthDiffServiceTest {

    private static final int SNAPSHOT_DEPTH_LIMIT = 1000;
    private static final int MAX_BUFFERED_EVENTS = 10;
    private static final int TOP_N_DEPTH = 10;

    private StubSymbolStateStore stateStore;
    private StubSnapshotPort snapshotPort;
    private RecordingBboPublisher bboPublisher;
    private RecordingTopNPublisher topNPublisher;
    private ProcessDepthDiffService service;

    @BeforeEach
    void setUp() {
        stateStore = new StubSymbolStateStore();
        snapshotPort = new StubSnapshotPort();
        bboPublisher = new RecordingBboPublisher();
        topNPublisher = new RecordingTopNPublisher();
        service = createService(MAX_BUFFERED_EVENTS);
    }

    private ProcessDepthDiffService createService(int maxBufferedEvents) {
        OrderBookApplier orderBookApplier = new OrderBookApplier();
        BinanceSpotSyncPolicy syncPolicy = new BinanceSpotSyncPolicy();
        SymbolStateLifecycleService lifecycleService = new SymbolStateLifecycleService(stateStore);
        MarketStatePublisher marketStatePublisher = new MarketStatePublisher(
                new BboStateMapper(),
                new OrderBookTopNStateMapper(),
                bboPublisher,
                topNPublisher,
                TOP_N_DEPTH
        );
        LiveOrderBookUpdateService liveOrderBookUpdateService = new LiveOrderBookUpdateService(
                orderBookApplier,
                syncPolicy,
                stateStore,
                lifecycleService,
                marketStatePublisher
        );
        DepthDiffBootstrapService depthDiffBootstrapService = new DepthDiffBootstrapService(
                orderBookApplier,
                syncPolicy,
                snapshotPort,
                stateStore,
                lifecycleService,
                marketStatePublisher,
                SNAPSHOT_DEPTH_LIMIT
        );
        return new ProcessDepthDiffService(
                stateStore,
                depthDiffBootstrapService,
                liveOrderBookUpdateService,
                lifecycleService,
                maxBufferedEvents
        );
    }

    @Nested
    class Bootstrap {

        @Test
        void successfulBootstrap_goesLive() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));

            service.process(
                    event(98, 105,
                            List.of(new PriceLevel("49999.00", "0.5")),
                            List.of(new PriceLevel("50002.00", "0.5"))),
                    ctx(1));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, state.getStatus());
            assertTrue(state.isTrusted());
            assertEquals(105, state.getLocalUpdateId());
            assertEquals(100, state.getLastSnapshotUpdateId());
            assertEquals(ScaledDecimal.parse("50000.00"), state.getOrderBook().bestBid());
            assertTrue(state.getBufferedEvents().isEmpty());
        }

        @Test
        void snapshotTooOld_goesResyncing() {
            snapshotPort.setSnapshot(snapshot(50, List.of(), List.of()));

            service.process(event(100, 110, List.of(), List.of()), ctx(1));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            assertFalse(state.isTrusted());
        }

        @Test
        void snapshotCatchesUpFully_bufferEmptyAfterDiscard_goesLive() {
            snapshotPort.setSnapshot(snapshot(200, List.of(), List.of()));

            service.process(event(90, 95, List.of(), List.of()), ctx(1));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, state.getStatus());
            assertTrue(state.isTrusted());
            assertEquals(200, state.getLocalUpdateId());
        }

        @Test
        void snapshotLoadException_goesResyncing() {
            snapshotPort.setException(new RuntimeException("connection refused"));

            service.process(event(100, 110, List.of(), List.of()), ctx(1));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
        }

        @Test
        void clearsFirstBufferedUpdateIdAfterSuccessfulBootstrap() {
            snapshotPort.setSnapshot(snapshot(100, List.of(), List.of()));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertNull(state.getFirstBufferedUpdateId());
        }

        @Test
        void noBridgingEvent_goesResyncing() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
            state.bufferEvent(new BufferedDepthDiff(event(100, 100, List.of(), List.of()), ctx(90)));
            state.bufferEvent(new BufferedDepthDiff(event(107, 110, List.of(), List.of()), ctx(91)));
            state.setFirstBufferedUpdateId(100L);
            stateStore.save(state);

            snapshotPort.setSnapshot(snapshot(105, List.of(), List.of()));

            service.process(event(108, 111, List.of(), List.of()), ctx(1));

            SymbolState after = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, after.getStatus());
            assertFalse(after.isTrusted());
        }

        @Test
        void gapDuringReplay_goesResyncing() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
            state.bufferEvent(new BufferedDepthDiff(event(100, 101, List.of(), List.of()), ctx(100)));
            state.bufferEvent(new BufferedDepthDiff(event(103, 105, List.of(), List.of()), ctx(101)));
            state.setFirstBufferedUpdateId(100L);
            stateStore.save(state);

            snapshotPort.setSnapshot(snapshot(100, List.of(), List.of()));

            service.process(event(104, 106, List.of(), List.of()), ctx(1));

            SymbolState after = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, after.getStatus());
        }

        @Test
        void bootstrapAlreadyInProgress_buffersOnlyWithoutRestart() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.SNAPSHOT_LOADING);
            state.setBootstrapInProgress(true);
            stateStore.save(state);

            service.process(event(120, 125, List.of(), List.of()), ctx(1));

            SymbolState after = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.SNAPSHOT_LOADING, after.getStatus());
            assertTrue(after.isBootstrapInProgress());
            assertEquals(1, after.getBufferedEvents().size());
            assertEquals(0, snapshotPort.getLoadCalls());
        }

        @Test
        void replayUsesBufferedEventContext_forLastProcessedOffset() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
            state.bufferEvent(new BufferedDepthDiff(event(101, 103, List.of(), List.of()), ctx(10)));
            state.bufferEvent(new BufferedDepthDiff(event(104, 105, List.of(), List.of()), ctx(11)));
            state.setFirstBufferedUpdateId(101L);
            stateStore.save(state);

            snapshotPort.setSnapshot(snapshot(100, List.of(), List.of()));

            service.process(event(90, 95, List.of(), List.of()), ctx(50));

            SymbolState after = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, after.getStatus());
            assertEquals(11, after.getLastProcessedOffset());
        }

        @Test
        void bufferOverflow_entersResyncing() {
            service = createService(1);

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.SNAPSHOT_LOADING);
            state.setBootstrapInProgress(true);
            state.bufferEvent(new BufferedDepthDiff(event(100, 101, List.of(), List.of()), ctx(1)));
            state.setFirstBufferedUpdateId(100L);
            stateStore.save(state);

            service.process(event(102, 103, List.of(), List.of()), ctx(2));

            SymbolState after = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, after.getStatus());
            assertFalse(after.isTrusted());
        }
    }

    @Nested
    class Live {

        @BeforeEach
        void bootstrapToLive() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));
        }

        @Test
        void apply_updatesLocalUpdateIdAndBook() {
            service.process(
                    event(106, 110,
                            List.of(new PriceLevel("49999.00", "2.0")),
                            List.of()),
                    ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(110, state.getLocalUpdateId());
            assertEquals(SymbolStateStatus.LIVE, state.getStatus());
            assertTrue(state.getOrderBook().getBids().containsKey(ScaledDecimal.parse("49999.00")));
        }

        @Test
        void ignore_doesNotMutateBook() {
            int bidsBefore = stateStore.loadOrCreate("BTCUSDT", "binance").getOrderBook().getBids().size();

            service.process(
                    event(90, 100,
                            List.of(new PriceLevel("48000.00", "5.0")),
                            List.of()),
                    ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(105, state.getLocalUpdateId());
            assertEquals(bidsBefore, state.getOrderBook().getBids().size());
        }

        @Test
        void gap_goesResyncing() {
            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            assertFalse(state.isTrusted());
        }
    }

    @Nested
    class Resyncing {

        @Test
        void resyncing_restartsBootstrapSuccessfully() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            service.process(event(200, 210, List.of(), List.of()), ctx(2));
            assertEquals(SymbolStateStatus.RESYNCING,
                    stateStore.loadOrCreate("BTCUSDT", "binance").getStatus());

            snapshotPort.setSnapshot(snapshot(300,
                    List.of(new PriceLevel("51000.00", "1.0")),
                    List.of(new PriceLevel("51001.00", "1.0"))));
            service.process(event(298, 310, List.of(), List.of()), ctx(3));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, state.getStatus());
            assertTrue(state.isTrusted());
            assertEquals(310, state.getLocalUpdateId());
            assertEquals(300, state.getLastSnapshotUpdateId());
        }
    }

    @Nested
    class Publishing {

        @Test
        void publishesAfterSuccessfulBootstrapToLive() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            assertEquals(1, bboPublisher.published.size());
            assertEquals(1, topNPublisher.published.size());

            BboStateEvent bbo = bboPublisher.published.get(0);
            assertEquals("50000.00000000", bbo.bestBid().price());
            assertEquals("50001.00000000", bbo.bestAsk().price());
            assertTrue(bbo.trusted());
        }

        @Test
        void publishesAfterLiveApply() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            bboPublisher.published.clear();
            topNPublisher.published.clear();

            service.process(
                    event(106, 110,
                            List.of(new PriceLevel("49999.00", "2.0")),
                            List.of()),
                    ctx(2));

            assertEquals(1, bboPublisher.published.size());
            assertEquals(1, topNPublisher.published.size());
        }

        @Test
        void doesNotPublishDuringBuffering() {
            snapshotPort.setSnapshot(null);
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
            state.setBootstrapInProgress(true);
            stateStore.save(state);

            service.process(event(100, 105, List.of(), List.of()), ctx(1));

            assertTrue(bboPublisher.published.isEmpty());
            assertTrue(topNPublisher.published.isEmpty());
        }

        @Test
        void doesNotPublishDuringSnapshotLoading() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.SNAPSHOT_LOADING);
            state.setBootstrapInProgress(true);
            stateStore.save(state);

            service.process(event(100, 105, List.of(), List.of()), ctx(1));

            assertTrue(bboPublisher.published.isEmpty());
            assertTrue(topNPublisher.published.isEmpty());
        }

        @Test
        void doesNotPublishDuringApplyingBuffer() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.APPLYING_BUFFER);
            state.setBootstrapInProgress(true);
            stateStore.save(state);

            service.process(event(100, 105, List.of(), List.of()), ctx(1));

            assertTrue(bboPublisher.published.isEmpty());
            assertTrue(topNPublisher.published.isEmpty());
        }

        @Test
        void doesNotPublishDuringResyncing() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            bboPublisher.published.clear();
            topNPublisher.published.clear();

            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            assertTrue(bboPublisher.published.isEmpty());
            assertTrue(topNPublisher.published.isEmpty());
        }

        @Test
        void doesNotPublishBboWhenBookHasNoBidsOrAsks() {
            snapshotPort.setSnapshot(snapshot(200, List.of(), List.of()));

            service.process(event(90, 95, List.of(), List.of()), ctx(1));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, state.getStatus());
            assertTrue(state.isTrusted());

            assertTrue(bboPublisher.published.isEmpty());
            assertEquals(1, topNPublisher.published.size());
        }

        @Test
        void topNEventContainsCorrectDepthAndLevels() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0"), new PriceLevel("49999.00", "2.0")),
                    List.of(new PriceLevel("50001.00", "1.0"), new PriceLevel("50002.00", "3.0"))));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            assertEquals(1, topNPublisher.published.size());
            OrderBookTopNStateEvent topN = topNPublisher.published.get(0);
            assertEquals(TOP_N_DEPTH, topN.depth());
            assertEquals(2, topN.bids().size());
            assertEquals(2, topN.asks().size());
            assertEquals("50000.00000000", topN.bids().get(0).price());
            assertEquals("49999.00000000", topN.bids().get(1).price());
            assertEquals("50001.00000000", topN.asks().get(0).price());
            assertEquals("50002.00000000", topN.asks().get(1).price());
            assertTrue(topN.trusted());
        }

        @Test
        void publishesAfterResyncRecovery() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            bboPublisher.published.clear();
            topNPublisher.published.clear();

            snapshotPort.setSnapshot(snapshot(300,
                    List.of(new PriceLevel("51000.00", "1.0")),
                    List.of(new PriceLevel("51001.00", "1.0"))));
            service.process(event(298, 310, List.of(), List.of()), ctx(3));

            assertFalse(bboPublisher.published.isEmpty());
            assertFalse(topNPublisher.published.isEmpty());
            assertEquals("51000.00000000", bboPublisher.published.get(0).bestBid().price());
        }

        @Test
        void bboMetadataContainsSymbolAndVenue() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevel("50000.00", "1.0")),
                    List.of(new PriceLevel("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            BboStateEvent bbo = bboPublisher.published.get(0);
            assertEquals("BTCUSDT", bbo.metadata().symbol());
            assertEquals("binance", bbo.metadata().exchange());
            assertEquals("spot", bbo.metadata().marketType());
            assertEquals("BTCUSDT", bbo.metadata().instrumentId());
        }
    }

    private static DepthDiffEvent event(long firstUpdateId, long finalUpdateId,
                                        List<PriceLevel> bids, List<PriceLevel> asks) {
        long now = System.currentTimeMillis();
        var metadata = new Metadata(1, "depthDiff", "binance", "spot",
                "BTC", "USDT", "BTCUSDT", "BTCUSDT", "evt-1", "stream-1",
                now, now, now);
        return new DepthDiffEvent(metadata, now, firstUpdateId, finalUpdateId, null, bids, asks);
    }

    private static OrderBookSnapshot snapshot(long lastUpdateId,
                                              List<PriceLevel> bids,
                                              List<PriceLevel> asks) {
        return new OrderBookSnapshot("BTCUSDT", "binance", lastUpdateId,
                bids, asks, 1000, System.currentTimeMillis());
    }

    private static KafkaMessageContext ctx(long offset) {
        return new KafkaMessageContext("BTCUSDT", 0, offset);
    }

    static class StubSymbolStateStore implements SymbolStateStorePort {
        private final ConcurrentMap<String, SymbolState> states = new ConcurrentHashMap<>();

        @Override
        public SymbolState loadOrCreate(String symbol, String venue) {
            return states.computeIfAbsent(venue + ":" + symbol, k -> new SymbolState(symbol, venue));
        }

        @Override
        public void save(SymbolState state) {
            states.put(state.getVenue() + ":" + state.getSymbol(), state);
        }
    }

    static class StubSnapshotPort implements BinanceSpotSnapshotApiService {
        private OrderBookSnapshot snapshot;
        private RuntimeException exception;
        private int loadCalls;

        void setSnapshot(OrderBookSnapshot snapshot) {
            this.snapshot = snapshot;
            this.exception = null;
        }

        void setException(RuntimeException exception) {
            this.exception = exception;
            this.snapshot = null;
        }

        int getLoadCalls() {
            return loadCalls;
        }

        @Override
        public OrderBookSnapshot load(String symbol, int depthLimit) {
            loadCalls++;
            if (exception != null) throw exception;
            return snapshot;
        }
    }

    static class RecordingBboPublisher implements PublishBboStatePort {
        final List<BboStateEvent> published = new ArrayList<>();

        @Override
        public void publish(BboStateEvent event) {
            published.add(event);
        }
    }

    static class RecordingTopNPublisher implements PublishOrderBookTopNStatePort {
        final List<OrderBookTopNStateEvent> published = new ArrayList<>();

        @Override
        public void publish(OrderBookTopNStateEvent event) {
            published.add(event);
        }
    }
}
