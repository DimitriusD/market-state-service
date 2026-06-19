package com.trading.mss.service;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.mapper.BboStateMapper;
import com.trading.mss.mapper.OrderBookDepthStateMapper;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.orderbook.BboStateDto;
import com.trading.mss.dto.orderbook.OrderBookDepthStateDto;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookDepthStatePort;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.handler.BootstrapPhaseStateHandler;
import com.trading.mss.service.handler.BufferingDiffsStateHandler;
import com.trading.mss.service.handler.DepthDiffStateHandlerRegistry;
import com.trading.mss.service.handler.InitDepthDiffStateHandler;
import com.trading.mss.service.handler.LiveDepthDiffStateHandler;
import com.trading.mss.service.handler.ResyncingDepthDiffStateHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class ProcessDepthDiffServiceTest {

    private static final int SNAPSHOT_DEPTH_LIMIT = 1000;
    private static final int MAX_BUFFERED_EVENTS = 10;
    private static final int PUBLISHED_LEVELS = 10;
    private static final long BOOTSTRAP_COOLDOWN_MS = 5000;

    private StubSymbolStateStore stateStore;
    private StubAsyncSnapshotPort snapshotPort;
    private RecordingBboPublisher bboPublisher;
    private RecordingTopNPublisher topNPublisher;
    private MutableClock clock;
    private ProcessDepthDiffService service;

    @BeforeEach
    void setUp() {
        stateStore = new StubSymbolStateStore();
        snapshotPort = new StubAsyncSnapshotPort();
        bboPublisher = new RecordingBboPublisher();
        topNPublisher = new RecordingTopNPublisher();
        // Start at a realistic epoch so the first bootstrap (lastBootstrapAttemptTs=0) is never gated.
        clock = new MutableClock(1_700_000_000_000L);
        service = createService(MAX_BUFFERED_EVENTS);
    }

    private ProcessDepthDiffService createService(int maxBufferedEvents) {
        OrderBookApplier orderBookApplier = new OrderBookApplier();
        BinanceSpotSyncPolicy syncPolicy = new BinanceSpotSyncPolicy();
        SymbolStateLifecycleService lifecycleService = new SymbolStateLifecycleService(stateStore);
        MarketStatePublisher marketStatePublisher = new MarketStatePublisher(
                new BboStateMapper(),
                new OrderBookDepthStateMapper(),
                bboPublisher,
                topNPublisher,
                PUBLISHED_LEVELS
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
                SNAPSHOT_DEPTH_LIMIT,
                clock,
                BOOTSTRAP_COOLDOWN_MS
        );
        DepthDiffBufferService bufferService = new DepthDiffBufferService(lifecycleService, maxBufferedEvents);

        BootstrapPhaseStateHandler bootstrapPhaseHandler =
                new BootstrapPhaseStateHandler(bufferService, depthDiffBootstrapService, stateStore);

        DepthDiffStateHandlerRegistry registry = new DepthDiffStateHandlerRegistry(List.of(
                new InitDepthDiffStateHandler(bufferService, depthDiffBootstrapService),
                new BufferingDiffsStateHandler(bufferService, depthDiffBootstrapService),
                bootstrapPhaseHandler,
                new LiveDepthDiffStateHandler(liveOrderBookUpdateService),
                new ResyncingDepthDiffStateHandler(bufferService, depthDiffBootstrapService, lifecycleService)
        ));
        registry.registerAdditionalStatus(SymbolStateStatus.APPLYING_BUFFER, bootstrapPhaseHandler);

        return new ProcessDepthDiffService(stateStore, registry);
    }

    @Nested
    class Bootstrap {

        @Test
        void successfulBootstrap_goesLive() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));

            service.process(
                    event(98, 105,
                            List.of(new PriceLevelDto("49999.00", "0.5")),
                            List.of(new PriceLevelDto("50002.00", "0.5"))),
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
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));
        }

        @Test
        void apply_updatesLocalUpdateIdAndBook() {
            service.process(
                    event(106, 110,
                            List.of(new PriceLevelDto("49999.00", "2.0")),
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
                            List.of(new PriceLevelDto("48000.00", "5.0")),
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
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            service.process(event(200, 210, List.of(), List.of()), ctx(2));
            assertEquals(SymbolStateStatus.RESYNCING,
                    stateStore.loadOrCreate("BTCUSDT", "binance").getStatus());

            snapshotPort.setSnapshot(snapshot(300,
                    List.of(new PriceLevelDto("51000.00", "1.0")),
                    List.of(new PriceLevelDto("51001.00", "1.0"))));
            clock.advance(BOOTSTRAP_COOLDOWN_MS + 1);
            service.process(event(298, 310, List.of(), List.of()), ctx(3));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, state.getStatus());
            assertTrue(state.isTrusted());
            assertEquals(310, state.getLocalUpdateId());
            assertEquals(300, state.getLastSnapshotUpdateId());
        }
    }

    @Nested
    class BootstrapCooldown {

        @Test
        void failingBootstrap_doesNotRetryWithinCooldown_keepsBuffering() {
            // Every snapshot load throws (simulating Binance 429/418).
            snapshotPort.setException(new RuntimeException("HTTP 429"));

            service.process(event(100, 110, List.of(), List.of()), ctx(1));
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            int loadsAfterFirstAttempt = snapshotPort.getLoadCalls();
            assertTrue(loadsAfterFirstAttempt > 0);

            // A flood of further diffs arrives within the cooldown window: none must hit Binance.
            service.process(event(111, 120, List.of(), List.of()), ctx(2));
            service.process(event(121, 130, List.of(), List.of()), ctx(3));
            service.process(event(131, 140, List.of(), List.of()), ctx(4));

            assertEquals(loadsAfterFirstAttempt, snapshotPort.getLoadCalls(),
                    "no new snapshot HTTP calls should happen during the cooldown");
            SymbolState during = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.BUFFERING_DIFFS, during.getStatus());
            assertFalse(during.getBufferedEvents().isEmpty());
        }

        @Test
        void bootstrapRetried_afterCooldownElapses() {
            snapshotPort.setException(new RuntimeException("HTTP 429"));
            service.process(event(100, 110, List.of(), List.of()), ctx(1));
            int loadsAfterFirstAttempt = snapshotPort.getLoadCalls();

            service.process(event(111, 120, List.of(), List.of()), ctx(2));
            assertEquals(loadsAfterFirstAttempt, snapshotPort.getLoadCalls());

            clock.advance(BOOTSTRAP_COOLDOWN_MS + 1);
            service.process(event(121, 130, List.of(), List.of()), ctx(3));

            assertTrue(snapshotPort.getLoadCalls() > loadsAfterFirstAttempt,
                    "snapshot should be retried once the cooldown has elapsed");
        }

        @Test
        void firstBootstrap_isNotGated() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            assertEquals(SymbolStateStatus.LIVE,
                    stateStore.loadOrCreate("BTCUSDT", "binance").getStatus());
        }
    }

    @Nested
    class AsyncBootstrap {

        @Test
        void snapshotLoadsAsync_appliedOnLaterEvent_notWhileInFlight() {
            snapshotPort.setManualCompletion(true);
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));

            // First event submits the fetch but does NOT block; the future is still in flight.
            service.process(event(98, 105, List.of(), List.of()), ctx(1));
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.SNAPSHOT_LOADING, state.getStatus());
            assertTrue(state.isBootstrapInProgress());
            assertEquals(1, snapshotPort.getLoadCalls());

            // Diffs that arrive while loading are buffered, not applied, and do not re-submit.
            service.process(event(106, 110, List.of(), List.of()), ctx(2));
            assertEquals(SymbolStateStatus.SNAPSHOT_LOADING,
                    stateStore.loadOrCreate("BTCUSDT", "binance").getStatus());
            assertEquals(1, snapshotPort.getLoadCalls());

            // Snapshot completes in the background; the next consumer-thread event applies it.
            snapshotPort.completePending();
            service.process(event(111, 115, List.of(), List.of()), ctx(3));

            SymbolState after = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, after.getStatus());
            assertTrue(after.isTrusted());
            assertEquals(115, after.getLocalUpdateId());
            assertEquals(100, after.getLastSnapshotUpdateId());
        }

        @Test
        void asyncFetchFailure_goesResyncing() {
            snapshotPort.setManualCompletion(true);
            snapshotPort.setException(new RuntimeException("connection reset"));

            service.process(event(100, 110, List.of(), List.of()), ctx(1));
            assertEquals(SymbolStateStatus.SNAPSHOT_LOADING,
                    stateStore.loadOrCreate("BTCUSDT", "binance").getStatus());

            snapshotPort.completePending();
            service.process(event(111, 120, List.of(), List.of()), ctx(2));

            SymbolState after = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, after.getStatus());
            assertFalse(after.isTrusted());
        }
    }

    @Nested
    class Publishing {

        @Test
        void publishesAfterSuccessfulBootstrapToLive() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            assertEquals(1, bboPublisher.published.size());
            assertEquals(1, topNPublisher.published.size());

            BboStateDto bbo = bboPublisher.published.getFirst();
            assertEquals("50000.00000000", bbo.bestBid().price());
            assertEquals("50001.00000000", bbo.bestAsk().price());
            assertEquals(BookSyncStatus.IN_SYNC, bbo.syncStatus());
        }

        @Test
        void publishesAfterLiveApply() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            bboPublisher.published.clear();
            topNPublisher.published.clear();

            service.process(
                    event(106, 110,
                            List.of(new PriceLevelDto("49999.00", "2.0")),
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
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
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
                    List.of(new PriceLevelDto("50000.00", "1.0"), new PriceLevelDto("49999.00", "2.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"), new PriceLevelDto("50002.00", "3.0"))));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            assertEquals(1, topNPublisher.published.size());
            OrderBookDepthStateDto depthState = topNPublisher.published.getFirst();
            assertEquals(PUBLISHED_LEVELS, depthState.publishedLevels());
            assertEquals(2, depthState.bidLevels().size());
            assertEquals(2, depthState.askLevels().size());
            assertEquals("50000.00000000", depthState.bidLevels().get(0).price());
            assertEquals("49999.00000000", depthState.bidLevels().get(1).price());
            assertEquals("50001.00000000", depthState.askLevels().get(0).price());
            assertEquals("50002.00000000", depthState.askLevels().get(1).price());
            assertEquals(BookSyncStatus.IN_SYNC, depthState.syncStatus());
        }

        @Test
        void publishesAfterResyncRecovery() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            bboPublisher.published.clear();
            topNPublisher.published.clear();

            snapshotPort.setSnapshot(snapshot(300,
                    List.of(new PriceLevelDto("51000.00", "1.0")),
                    List.of(new PriceLevelDto("51001.00", "1.0"))));
            clock.advance(BOOTSTRAP_COOLDOWN_MS + 1);
            service.process(event(298, 310, List.of(), List.of()), ctx(3));

            assertFalse(bboPublisher.published.isEmpty());
            assertFalse(topNPublisher.published.isEmpty());
            assertEquals("51000.00000000", bboPublisher.published.getFirst().bestBid().price());
        }

        @Test
        void bboMetadataContainsSymbolAndVenue() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            BboStateDto bbo = bboPublisher.published.getFirst();
            assertEquals("BTCUSDT", bbo.metadata().symbol());
            assertEquals("binance", bbo.metadata().exchange());
            assertEquals("spot", bbo.metadata().marketType());
            assertEquals("BTCUSDT", bbo.metadata().instrumentId());
            assertEquals("BTC", bbo.metadata().base());
            assertEquals("USDT", bbo.metadata().quote());
        }
    }

    private static DepthDiffDto event(long firstUpdateId, long finalUpdateId,
                                      List<PriceLevelDto> bids, List<PriceLevelDto> asks) {
        long now = System.currentTimeMillis();
        var metadata = new MetadataDto(1, "depthDiff", "binance", "spot",
                "BTC", "USDT", "BTCUSDT", "BTCUSDT", "evt-1", "stream-1",
                now, now, now);
        return new DepthDiffDto(metadata, now, firstUpdateId, finalUpdateId, null, bids, asks);
    }

    private static OrderBookSnapshot snapshot(long lastUpdateId,
                                              List<PriceLevelDto> bids,
                                              List<PriceLevelDto> asks) {
        return new OrderBookSnapshot("BTCUSDT", "binance", lastUpdateId,
                bids, asks, 1000, System.currentTimeMillis());
    }

    private static KafkaMessageContext ctx(long offset) {
        return new KafkaMessageContext("BTCUSDT", 0, offset);
    }

    static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long startMillis) {
            this.millis = startMillis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
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

    /**
     * Async snapshot stub. By default it completes futures synchronously (so most tests behave like
     * the previous blocking flow). With {@code setManualCompletion(true)} it returns un-completed
     * futures that the test completes via {@link #completePending()}, exercising the real async path
     * where the snapshot arrives only on a later consumer-thread event.
     */
    static class StubAsyncSnapshotPort implements AsyncSnapshotPort {
        private OrderBookSnapshot snapshot;
        private RuntimeException exception;
        private int loadCalls;
        private boolean manualCompletion;
        private final List<CompletableFuture<OrderBookSnapshot>> pending = new ArrayList<>();

        void setSnapshot(OrderBookSnapshot snapshot) {
            this.snapshot = snapshot;
            this.exception = null;
        }

        void setException(RuntimeException exception) {
            this.exception = exception;
            this.snapshot = null;
        }

        void setManualCompletion(boolean manualCompletion) {
            this.manualCompletion = manualCompletion;
        }

        int getLoadCalls() {
            return loadCalls;
        }

        void completePending() {
            for (CompletableFuture<OrderBookSnapshot> future : pending) {
                if (exception != null) {
                    future.completeExceptionally(exception);
                } else {
                    future.complete(snapshot);
                }
            }
            pending.clear();
        }

        @Override
        public CompletableFuture<OrderBookSnapshot> fetch(String symbol, int depthLimit) {
            loadCalls++;
            if (manualCompletion) {
                CompletableFuture<OrderBookSnapshot> future = new CompletableFuture<>();
                pending.add(future);
                return future;
            }
            if (exception != null) {
                return CompletableFuture.failedFuture(exception);
            }
            return CompletableFuture.completedFuture(snapshot);
        }
    }

    static class RecordingBboPublisher implements PublishBboStatePort {
        final List<BboStateDto> published = new ArrayList<>();

        @Override
        public void publish(BboStateDto event) {
            published.add(event);
        }
    }

    static class RecordingTopNPublisher implements PublishOrderBookDepthStatePort {
        final List<OrderBookDepthStateDto> published = new ArrayList<>();

        @Override
        public void publish(OrderBookDepthStateDto event) {
            published.add(event);
        }
    }
}
