package com.trading.mss.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;

@Getter
public class SymbolState {

    // Immutable identity: instrumentId is the primary MSS identity (state-store key, stripe hash,
    // output Kafka key); venue/marketType/symbol are routing attributes fixed at creation.
    private final String instrumentId;
    private final String symbol;
    private final String venue;
    private final String marketType;

    // Cached identity key: all four components are immutable, so key() (called on every save/tick)
    // returns this pre-built instance instead of reallocating. Excluded from the generated getter.
    @Getter(lombok.AccessLevel.NONE)
    private final InstrumentKey key;

    private final OrderBook orderBook = new OrderBook();
    private final Deque<BufferedDepthDiff> bufferedEvents = new ArrayDeque<>();

    // Grouped mutable state — each group owns its mutation rules; none of them expose raw setters.
    private final SyncCounters counters = new SyncCounters();
    private final InputPosition input = new InputPosition();
    private final BootstrapProgress bootstrap = new BootstrapProgress();

    @Setter
    private SymbolStateStatus status = SymbolStateStatus.INIT;
    @Setter
    private boolean trusted = false;
    // Descriptive metadata, not identity — may be lazily set from incoming event metadata.
    @Setter
    private String base;
    @Setter
    private String quote;
    @Setter
    private long localUpdateId = -1;
    @Setter
    private Long previousLocalUpdateId = null;
    @Setter
    private long lastSnapshotUpdateId = -1;
    @Setter
    private long lastEventExchangeTs;
    @Setter
    private long lastEventReceivedTs;
    @Setter
    private long lastEventProcessedTs;

    // Wall-clock time of the last successful apply (diff or snapshot) on THIS host; the staleness
    // watchdog compares against it. Exchange/received timestamps are unsuitable (clock skew).
    @Setter
    private long lastAppliedWallTs;
    // Edge-trigger flag: a soft-stale status was published and not yet cleared by a fresh apply.
    @Setter
    private boolean staleReported = false;

    // Monotonic per-instrument sequence assigned to each published OrderBookL2SnapshotEvent.
    // Advances only through nextStateSeq() — deliberately no setter.
    private long stateSeq = 0;

    public SymbolState(InstrumentKey key) {
        this.key = key;
        this.instrumentId = key.instrumentId();
        this.symbol = key.symbol();
        this.venue = key.exchange();
        this.marketType = key.marketType();
    }

    public InstrumentKey key() {
        return key;
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
}
