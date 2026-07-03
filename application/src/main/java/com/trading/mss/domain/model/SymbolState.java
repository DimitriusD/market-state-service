package com.trading.mss.domain.model;

import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.market.DepthDiffDto;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

@Getter
@Setter
public class SymbolState {

    private final String symbol;
    private final String venue;

    private final OrderBook orderBook = new OrderBook();
    private final Deque<BufferedDepthDiff> bufferedEvents = new ArrayDeque<>();

    private final String marketType;

    private SymbolStateStatus status = SymbolStateStatus.INIT;
    private boolean trusted = false;
    private String base;
    private String quote;
    private String instrumentId;
    private long localUpdateId = -1;
    private Long previousLocalUpdateId = null;
    private long lastProcessedOffset = -1;
    private Long firstBufferedUpdateId = null;
    private long lastSnapshotUpdateId = -1;
    private boolean bootstrapInProgress = false;
    private long lastBootstrapAttemptTs = 0;
    private transient CompletableFuture<OrderBookSnapshot> pendingSnapshot;
    private long lastEventExchangeTs;
    private long lastEventReceivedTs;
    private long lastEventProcessedTs;

    // Monotonic per-instrument sequence assigned to each published OrderBookL2SnapshotEvent.
    private long stateSeq = 0;

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

    public SymbolState(SymbolKey key) {
        this.symbol = key.symbol();
        this.venue = key.exchange();
        this.marketType = key.marketType();
    }

    public SymbolKey key() {
        return new SymbolKey(venue, marketType, symbol);
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
