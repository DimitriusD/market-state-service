package com.trading.mss.domain.model;

import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.market.DepthDiffDto;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;

@Getter
@Setter
public class SymbolState {

    // Immutable identity: instrumentId is the primary MSS identity (state-store key, stripe hash,
    // output Kafka key); venue/marketType/symbol are routing attributes fixed at creation.
    private final String instrumentId;
    private final String symbol;
    private final String venue;
    private final String marketType;

    private final OrderBook orderBook = new OrderBook();
    private final Deque<BufferedDepthDiff> bufferedEvents = new ArrayDeque<>();

    private SymbolStateStatus status = SymbolStateStatus.INIT;
    private boolean trusted = false;
    // Descriptive metadata, not identity — may be lazily set from incoming event metadata.
    private String base;
    private String quote;
    private long localUpdateId = -1;
    private Long previousLocalUpdateId = null;
    private long lastProcessedOffset = -1;
    private Long firstBufferedUpdateId = null;
    private long lastSnapshotUpdateId = -1;
    private boolean bootstrapInProgress = false;
    private long lastBootstrapAttemptTs = 0;
    private long lastEventExchangeTs;
    private long lastEventReceivedTs;
    private long lastEventProcessedTs;

    // Wall-clock time of the last successful apply (diff or snapshot) on THIS host; the staleness
    // watchdog compares against it. Exchange/received timestamps are unsuitable (clock skew).
    private long lastAppliedWallTs;
    // Edge-trigger flag: a soft-stale status was published and not yet cleared by a fresh apply.
    private boolean staleReported = false;

    // Monotonic per-instrument sequence assigned to each published OrderBookL2SnapshotEvent.
    private long stateSeq = 0;

    // Incremented on every snapshot-fetch submission and on every bootstrap reset; a snapshot
    // callback carrying an older epoch is stale (superseded fetch) and must be discarded.
    // Plain long on purpose: only ever touched from this symbol's serialized commands.
    private long bootstrapEpoch = 0;

    // Diagnostic counters surfaced in version/quality/status events.
    private long gapCount = 0;
    private long resyncCount = 0;
    private long duplicateCount = 0;
    private long snapshotRetryCount = 0;

    // Last consumed input position, used to build source/status when no triggering event is at hand.
    private String lastProcessedTopic;
    private String lastProcessedKey;
    private int lastProcessedPartition = -1;

    // Update ids of the last input depth diff applied to the book.
    private Long lastInputFirstUpdateId;
    private Long lastInputFinalUpdateId;
    private Long lastInputPreviousFinalUpdateId;

    public SymbolState(InstrumentKey key) {
        this.instrumentId = key.instrumentId();
        this.symbol = key.symbol();
        this.venue = key.exchange();
        this.marketType = key.marketType();
    }

    public InstrumentKey key() {
        return new InstrumentKey(instrumentId, venue, marketType, symbol);
    }

    public void bufferEvent(BufferedDepthDiff event) {
        bufferedEvents.addLast(event);
    }

    public void clearBuffer() {
        bufferedEvents.clear();
    }

    public long nextStateSeq() {
        return ++stateSeq;
    }

    public long incrementBootstrapEpoch() {
        return ++bootstrapEpoch;
    }

    public void incrementGapCount() {
        gapCount++;
    }

    public void incrementResyncCount() {
        resyncCount++;
    }

    public void incrementDuplicateCount() {
        duplicateCount++;
    }

    public void incrementSnapshotRetryCount() {
        snapshotRetryCount++;
    }

    public void recordInputContext(DepthDiffDto event, KafkaMessageContext ctx) {
        if (ctx != null) {
            this.lastProcessedTopic = ctx.topic();
            this.lastProcessedKey = ctx.key();
            this.lastProcessedPartition = ctx.partition();
            this.lastProcessedOffset = ctx.offset();
        }
        if (event != null) {
            this.lastInputFirstUpdateId = event.firstUpdateId();
            this.lastInputFinalUpdateId = event.finalUpdateId();
            this.lastInputPreviousFinalUpdateId = event.previousFinalUpdateId();
        }
    }
}
