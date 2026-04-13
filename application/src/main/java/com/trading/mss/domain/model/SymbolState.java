package com.trading.mss.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;

@Getter
@Setter
public class SymbolState {

    private final String symbol;
    private final String venue;

    private final OrderBook orderBook = new OrderBook();
    private final Deque<BufferedDepthDiff> bufferedEvents = new ArrayDeque<>();

    private SymbolStateStatus status = SymbolStateStatus.INIT;
    private boolean trusted = false;
    private String marketType;
    private String base;
    private String quote;
    private String instrumentId;
    private long localUpdateId = -1;
    private long lastProcessedOffset = -1;
    private Long firstBufferedUpdateId = null;
    private long lastSnapshotUpdateId = -1;
    private boolean bootstrapInProgress = false;
    private long lastEventExchangeTs;
    private long lastEventReceivedTs;
    private long lastEventProcessedTs;

    public SymbolState(String symbol, String venue) {
        this.symbol = symbol;
        this.venue = venue;
    }

    public void bufferEvent(BufferedDepthDiff event) {
        bufferedEvents.addLast(event);
    }

    public void clearBuffer() {
        bufferedEvents.clear();
    }
}
