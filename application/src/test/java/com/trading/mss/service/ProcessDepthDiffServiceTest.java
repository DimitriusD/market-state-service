package com.trading.mss.service;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;
import com.trading.mss.dto.orderbook.OrderBookStatusDto;
import com.trading.mss.mapper.OrderBookL2SnapshotMapper;
import com.trading.mss.mapper.OrderBookStatusMapper;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.PublishOrderBookL2SnapshotPort;
import com.trading.mss.port.output.PublishOrderBookStatusPort;
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

    private static final String INPUT_TOPIC = "canonical.market.depthdiff.v1";

    private StubSymbolStateStore stateStore;
    private StubAsyncSnapshotPort snapshotPort;
    private RecordingSnapshotPublisher snapshotPublisher;
    private RecordingStatusPublisher statusPublisher;
    private MutableClock clock;
    private ProcessDepthDiffService service;

    @BeforeEach
    void setUp() {
        stateStore = new StubSymbolStateStore();
        snapshotPort = new StubAsyncSnapshotPort();
        snapshotPublisher = new RecordingSnapshotPublisher();
        statusPublisher = new RecordingStatusPublisher();
        clock = new MutableClock(1_700_000_000_000L);
        service = createService(MAX_BUFFERED_EVENTS);
    }

    private ProcessDepthDiffService createService(int maxBufferedEvents) {
        OrderBookApplier orderBookApplier = new OrderBookApplier();
        BinanceSpotSyncPolicy syncPolicy = new BinanceSpotSyncPolicy();
        SymbolStateLifecycleService lifecycleService = new SymbolStateLifecycleService(
                stateStore, new OrderBookStatusMapper(clock), statusPublisher);
        MarketStatePublisher marketStatePublisher = new MarketStatePublisher(
                new OrderBookL2SnapshotMapper(clock),
                snapshotPublisher,
                PUBLISHED_LEVELS,
                SNAPSHOT_DEPTH_LIMIT
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
            assertLastStatusReason(OrderBookReason.SNAPSHOT_TOO_OLD);
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
            assertLastStatusReason(OrderBookReason.SNAPSHOT_LOAD_FAILED);
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
            assertLastStatusReason(OrderBookReason.NO_BRIDGING_EVENT);
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
            assertLastStatusReason(OrderBookReason.GAP_DURING_BUFFER_REPLAY);
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
            assertLastStatusReason(OrderBookReason.BUFFER_OVERFLOW);
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
        void ignore_doesNotMutateBook_andCountsDuplicate() {
            int bidsBefore = stateStore.loadOrCreate("BTCUSDT", "binance").getOrderBook().getBids().size();

            service.process(
                    event(90, 100,
                            List.of(new PriceLevelDto("48000.00", "5.0")),
                            List.of()),
                    ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(105, state.getLocalUpdateId());
            assertEquals(bidsBefore, state.getOrderBook().getBids().size());
            assertEquals(1, state.getDuplicateCount());
        }

        @Test
        void gap_goesResyncing() {
            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            assertFalse(state.isTrusted());
        }

        @Test
        void gap_publishesStatusAndIncrementsCounters_withoutSnapshot() {
            snapshotPublisher.published.clear();
            statusPublisher.published.clear();

            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertTrue(snapshotPublisher.published.isEmpty(), "gap must not publish a snapshot");
            assertLastStatusReason(OrderBookReason.GAP_DETECTED);
            assertEquals(SymbolStateStatus.RESYNCING, lastStatus().lifecycleStatus());
            assertEquals(BookSyncStatus.OUT_OF_SYNC, lastStatus().syncStatus());
            assertFalse(lastStatus().trusted());
            assertEquals(1, state.getGapCount());
            assertEquals(1, state.getResyncCount());
        }

        @Test
        void crossedBookAfterApply_goesResyncingWithoutPublishingSnapshot() {
            snapshotPublisher.published.clear();
            statusPublisher.published.clear();

            service.process(
                    event(106, 110,
                            List.of(new PriceLevelDto("50002.00", "1.0")),
                            List.of()),
                    ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            assertFalse(state.isTrusted());
            assertTrue(snapshotPublisher.published.isEmpty(), "crossed book must not publish a snapshot");
            assertLastStatusReason(OrderBookReason.CROSSED_BOOK);
            assertEquals(SymbolStateStatus.RESYNCING, lastStatus().lifecycleStatus());
            assertEquals(BookSyncStatus.OUT_OF_SYNC, lastStatus().syncStatus());
            assertFalse(lastStatus().trusted());
        }

        @Test
        void crossedBook_recoversViaResyncOnNextSnapshot() {
            service.process(
                    event(106, 110,
                            List.of(new PriceLevelDto("50002.00", "1.0")),
                            List.of()),
                    ctx(2));
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
            snapshotPort.setException(new RuntimeException("HTTP 429"));

            service.process(event(100, 110, List.of(), List.of()), ctx(1));
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            int loadsAfterFirstAttempt = snapshotPort.getLoadCalls();
            assertTrue(loadsAfterFirstAttempt > 0);

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

            service.process(event(98, 105, List.of(), List.of()), ctx(1));
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.SNAPSHOT_LOADING, state.getStatus());
            assertTrue(state.isBootstrapInProgress());
            assertEquals(1, snapshotPort.getLoadCalls());

            service.process(event(106, 110, List.of(), List.of()), ctx(2));
            assertEquals(SymbolStateStatus.SNAPSHOT_LOADING,
                    stateStore.loadOrCreate("BTCUSDT", "binance").getStatus());
            assertEquals(1, snapshotPort.getLoadCalls());

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
        void bootstrapSuccess_publishesOneSnapshotAndLiveStatus() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            assertEquals(1, snapshotPublisher.published.size());
            OrderBookL2SnapshotDto snap = snapshotPublisher.published.getFirst();
            assertEquals("50000.00000000", snap.bbo().bestBid().price());
            assertEquals("50001.00000000", snap.bbo().bestAsk().price());
            assertEquals(BookSyncStatus.IN_SYNC, snap.quality().syncStatus());
            assertTrue(snap.quality().trusted());
            assertEquals(OrderBookReason.NONE, snap.quality().reason());
            assertEquals(100, snap.version().lastSnapshotUpdateId());
            assertEquals(105, snap.version().exchangeUpdateId());

            OrderBookStatusDto live = lastStatus();
            assertEquals(SymbolStateStatus.LIVE, live.lifecycleStatus());
            assertEquals(BookSyncStatus.IN_SYNC, live.syncStatus());
            assertTrue(live.trusted());
            assertEquals(OrderBookReason.NONE, live.reason());
        }

        @Test
        void publishesExactlyOneSnapshotAfterLiveApply() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            snapshotPublisher.published.clear();

            service.process(
                    event(106, 110,
                            List.of(new PriceLevelDto("49999.00", "2.0")),
                            List.of()),
                    ctx(2));

            assertEquals(1, snapshotPublisher.published.size());
            OrderBookL2SnapshotDto snap = snapshotPublisher.published.getFirst();
            assertEquals(110, snap.version().exchangeUpdateId());
            assertEquals(INPUT_TOPIC, snap.source().inputTopic());
            assertEquals(2, snap.source().inputOffset());
            assertEquals(106L, snap.source().inputFirstUpdateId());
            assertEquals(110L, snap.source().inputFinalUpdateId());
        }

        @Test
        void stateSeqIsMonotonicAcrossSnapshots() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));
            service.process(event(106, 110, List.of(new PriceLevelDto("49999.00", "2.0")), List.of()), ctx(2));
            service.process(event(111, 115, List.of(new PriceLevelDto("49998.00", "2.0")), List.of()), ctx(3));

            assertEquals(3, snapshotPublisher.published.size());
            assertEquals(1, snapshotPublisher.published.get(0).version().stateSeq());
            assertEquals(2, snapshotPublisher.published.get(1).version().stateSeq());
            assertEquals(3, snapshotPublisher.published.get(2).version().stateSeq());
        }

        @Test
        void doesNotPublishSnapshotDuringBuffering() {
            snapshotPort.setSnapshot(null);
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.BUFFERING_DIFFS);
            state.setBootstrapInProgress(true);
            stateStore.save(state);

            service.process(event(100, 105, List.of(), List.of()), ctx(1));

            assertTrue(snapshotPublisher.published.isEmpty());
        }

        @Test
        void doesNotPublishSnapshotDuringSnapshotLoading() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.SNAPSHOT_LOADING);
            state.setBootstrapInProgress(true);
            stateStore.save(state);

            service.process(event(100, 105, List.of(), List.of()), ctx(1));

            assertTrue(snapshotPublisher.published.isEmpty());
        }

        @Test
        void doesNotPublishSnapshotDuringApplyingBuffer() {
            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            state.setStatus(SymbolStateStatus.APPLYING_BUFFER);
            state.setBootstrapInProgress(true);
            stateStore.save(state);

            service.process(event(100, 105, List.of(), List.of()), ctx(1));

            assertTrue(snapshotPublisher.published.isEmpty());
        }

        @Test
        void doesNotPublishSnapshotDuringResyncing() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            snapshotPublisher.published.clear();

            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.RESYNCING, state.getStatus());
            assertTrue(snapshotPublisher.published.isEmpty());
        }

        @Test
        void oneSidedBook_goesLiveButPublishesNoSnapshot() {
            snapshotPort.setSnapshot(snapshot(200, List.of(), List.of()));

            service.process(event(90, 95, List.of(), List.of()), ctx(1));

            SymbolState state = stateStore.loadOrCreate("BTCUSDT", "binance");
            assertEquals(SymbolStateStatus.LIVE, state.getStatus());
            assertTrue(state.isTrusted());
            assertTrue(snapshotPublisher.published.isEmpty(),
                    "incomplete (one-sided) book must not be published as a trusted snapshot");
        }

        @Test
        void snapshotDepthContainsSortedLevels() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0"), new PriceLevelDto("49999.00", "2.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"), new PriceLevelDto("50002.00", "3.0"))));

            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            assertEquals(1, snapshotPublisher.published.size());
            OrderBookL2SnapshotDto snap = snapshotPublisher.published.getFirst();
            assertEquals(2, snap.depth().bids().size());
            assertEquals(2, snap.depth().asks().size());
            assertEquals("50000.00000000", snap.depth().bids().get(0).price());
            assertEquals("49999.00000000", snap.depth().bids().get(1).price());
            assertEquals("50001.00000000", snap.depth().asks().get(0).price());
            assertEquals("50002.00000000", snap.depth().asks().get(1).price());
        }

        @Test
        void publishesAfterResyncRecovery() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            service.process(event(200, 210, List.of(), List.of()), ctx(2));

            snapshotPublisher.published.clear();

            snapshotPort.setSnapshot(snapshot(300,
                    List.of(new PriceLevelDto("51000.00", "1.0")),
                    List.of(new PriceLevelDto("51001.00", "1.0"))));
            clock.advance(BOOTSTRAP_COOLDOWN_MS + 1);
            service.process(event(298, 310, List.of(), List.of()), ctx(3));

            assertFalse(snapshotPublisher.published.isEmpty());
            assertEquals("51000.00000000", snapshotPublisher.published.getFirst().bbo().bestBid().price());
        }

        @Test
        void snapshotMetadataContainsSymbolAndVenue() {
            snapshotPort.setSnapshot(snapshot(100,
                    List.of(new PriceLevelDto("50000.00", "1.0")),
                    List.of(new PriceLevelDto("50001.00", "1.0"))));
            service.process(event(98, 105, List.of(), List.of()), ctx(1));

            OrderBookL2SnapshotDto snap = snapshotPublisher.published.getFirst();
            assertEquals("BTCUSDT", snap.metadata().symbol());
            assertEquals("binance", snap.metadata().exchange());
            assertEquals("spot", snap.metadata().marketType());
            assertEquals("BTCUSDT", snap.metadata().instrumentId());
            assertEquals("BTC", snap.metadata().base());
            assertEquals("USDT", snap.metadata().quote());
            assertEquals("ORDERBOOK_L2_SNAPSHOT", snap.metadata().eventType());
        }
    }

    private OrderBookStatusDto lastStatus() {
        assertFalse(statusPublisher.published.isEmpty(), "expected at least one status event");
        return statusPublisher.published.getLast();
    }

    private void assertLastStatusReason(OrderBookReason expected) {
        assertEquals(expected, lastStatus().reason());
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
        return new KafkaMessageContext(INPUT_TOPIC, "BTCUSDT", 0, offset);
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

    static class RecordingSnapshotPublisher implements PublishOrderBookL2SnapshotPort {
        final List<OrderBookL2SnapshotDto> published = new ArrayList<>();

        @Override
        public void publish(OrderBookL2SnapshotDto snapshot) {
            published.add(snapshot);
        }
    }

    static class RecordingStatusPublisher implements PublishOrderBookStatusPort {
        final List<OrderBookStatusDto> published = new ArrayList<>();

        @Override
        public void publish(OrderBookStatusDto status) {
            published.add(status);
        }
    }
}
