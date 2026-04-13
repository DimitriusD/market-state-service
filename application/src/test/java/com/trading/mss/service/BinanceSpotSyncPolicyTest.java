package com.trading.mss.service;

import com.trading.mss.domain.model.SyncDecision;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.common.MetadataDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BinanceSpotSyncPolicyTest {

    private BinanceSpotSyncPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new BinanceSpotSyncPolicy();
    }

    @Nested
    class Evaluate {

        @Test
        void noAnchorYet_shouldApply() {
            SymbolState state = new SymbolState("BTCUSDT", "binance");
            assertEquals(SyncDecision.APPLY, policy.evaluate(event(100, 105), state));
        }

        @Test
        void staleFinalUpdateId_shouldIgnore() {
            assertEquals(SyncDecision.IGNORE, policy.evaluate(event(190, 199), stateAt(200)));
        }

        @Test
        void duplicateExactFinalUpdateId_shouldIgnore() {
            assertEquals(SyncDecision.IGNORE, policy.evaluate(event(198, 200), stateAt(200)));
        }

        @Test
        void gapDetected_shouldResync() {
            assertEquals(SyncDecision.RESYNC, policy.evaluate(event(203, 210), stateAt(200)));
        }

        @Test
        void contiguousSequence_shouldApply() {
            assertEquals(SyncDecision.APPLY, policy.evaluate(event(201, 205), stateAt(200)));
        }

        @Test
        void overlappingButNotStale_shouldApply() {
            assertEquals(SyncDecision.APPLY, policy.evaluate(event(199, 205), stateAt(200)));
        }

        @Test
        void exactlyContiguous_shouldApply() {
            assertEquals(SyncDecision.APPLY, policy.evaluate(event(201, 201), stateAt(200)));
        }

        @Test
        void gapOfOne_shouldResync() {
            assertEquals(SyncDecision.RESYNC, policy.evaluate(event(202, 205), stateAt(200)));
        }
    }

    @Nested
    class SnapshotTooOld {

        @Test
        void tooOld_whenBelowFirstBufferedMinusOne() {
            assertTrue(policy.isSnapshotTooOld(98, 100));
        }

        @Test
        void notTooOld_whenEqualToFirstBufferedMinusOne() {
            assertFalse(policy.isSnapshotTooOld(99, 100));
        }

        @Test
        void notTooOld_whenEqualToFirstBuffered() {
            assertFalse(policy.isSnapshotTooOld(100, 100));
        }

        @Test
        void notTooOld_whenAboveFirstBuffered() {
            assertFalse(policy.isSnapshotTooOld(150, 100));
        }
    }

    @Nested
    class DiscardBufferedEvent {

        @Test
        void discard_whenFullyCoveredBySnapshot() {
            assertTrue(policy.shouldDiscardBufferedEvent(event(90, 100), 100));
        }

        @Test
        void keep_whenFinalUpdateIdExceedsSnapshot() {
            assertFalse(policy.shouldDiscardBufferedEvent(event(90, 101), 100));
        }

        @Test
        void discard_whenExactlyEqual() {
            assertTrue(policy.shouldDiscardBufferedEvent(event(95, 100), 100));
        }
    }

    @Nested
    class BridgingEvent {

        @Test
        void bridging_whenSnapshotInsideRange() {
            assertTrue(policy.isBridgingEvent(event(95, 105), 100));
        }

        @Test
        void bridging_whenSnapshotEqualsFirstUpdateId() {
            assertTrue(policy.isBridgingEvent(event(100, 105), 100));
        }

        @Test
        void notBridging_whenSnapshotPlusOneExceedsFinalUpdateId() {
            assertFalse(policy.isBridgingEvent(event(95, 100), 100));
        }

        @Test
        void bridging_whenEventStartsAtSnapshotPlusOne() {
            assertTrue(policy.isBridgingEvent(event(101, 105), 100));
        }

        @Test
        void notBridging_whenEventStartsAfterSnapshotPlusOne() {
            assertFalse(policy.isBridgingEvent(event(102, 105), 100));
        }

        @Test
        void notBridging_whenSnapshotPlusOneAboveRange() {
            assertFalse(policy.isBridgingEvent(event(95, 99), 100));
        }
    }

    private SymbolState stateAt(long localUpdateId) {
        SymbolState state = new SymbolState("BTCUSDT", "binance");
        state.setLocalUpdateId(localUpdateId);
        return state;
    }

    private DepthDiffDto event(long firstUpdateId, long finalUpdateId) {
        var metadata = new MetadataDto(1, "depthDiff", "binance", "spot",
                "BTC", "USDT", "BTCUSDT", "BTCUSDT", "evt-1", "stream-1",
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis());
        return new DepthDiffDto(metadata, System.currentTimeMillis(),
                firstUpdateId, finalUpdateId, null, List.of(), List.of());
    }
}
