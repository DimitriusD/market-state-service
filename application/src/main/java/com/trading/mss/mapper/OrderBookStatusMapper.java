package com.trading.mss.mapper;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.orderbook.OrderBookStatusDetailsDto;
import com.trading.mss.dto.orderbook.OrderBookStatusDto;
import lombok.RequiredArgsConstructor;

import java.time.Clock;

/**
 * Projects a {@link SymbolState} lifecycle transition (or failure) into an {@link OrderBookStatusDto}.
 *
 * <p>{@code triggeringEvent} and {@code ctx} may be {@code null} for bootstrap/status transitions that
 * are not driven by a single input event; in that case the last recorded input context on the state
 * is used for diagnostics.
 */
@RequiredArgsConstructor
public class OrderBookStatusMapper {

    private static final String EVENT_TYPE = "ORDERBOOK_STATUS";

    private final Clock clock;

    public OrderBookStatusDto project(
            SymbolState state,
            OrderBookReason reason,
            DepthDiffDto triggeringEvent,
            KafkaMessageContext ctx,
            String message) {

        long now = clock.millis();
        long eventExchangeTs = triggeringEvent != null ? triggeringEvent.metadataDto().exchangeTs() : 0;
        long eventReceivedTs = triggeringEvent != null ? triggeringEvent.metadataDto().receivedTs() : 0;

        MetadataDto metadata = StateMetadataFactory.build(
                state, EVENT_TYPE,
                "mss-orderbook-status-" + StateMetadataFactory.safeInstrumentId(state) + "-" + now,
                resolveTs(state.getLastEventExchangeTs(), eventExchangeTs, now),
                resolveTs(state.getLastEventReceivedTs(), eventReceivedTs, now),
                now);

        OrderBookStatusDetailsDto details = buildDetails(state, triggeringEvent, ctx, now);

        return new OrderBookStatusDto(
                metadata,
                state.getStatus(),
                syncStatusFor(state),
                state.isTrusted(),
                reason,
                details,
                message);
    }

    private OrderBookStatusDetailsDto buildDetails(SymbolState state, DepthDiffDto event, KafkaMessageContext ctx, long now) {
        OrderBook book = state.getOrderBook();
        PriceLevelDto bestBid = book.hasBids()
                ? new PriceLevelDto(ScaledDecimal.format(book.bestBid()), ScaledDecimal.format(book.getBids().get(book.bestBid())))
                : null;
        PriceLevelDto bestAsk = book.hasAsks()
                ? new PriceLevelDto(ScaledDecimal.format(book.bestAsk()), ScaledDecimal.format(book.getAsks().get(book.bestAsk())))
                : null;

        long exchangeTs = state.getLastEventExchangeTs();
        long stateAgeMs = exchangeTs > 0 ? now - exchangeTs : -1;

        return new OrderBookStatusDetailsDto(
                state.getLocalUpdateId(),
                positiveOrNull(state.getPreviousLocalUpdateId()),
                state.getLastSnapshotUpdateId(),
                state.getFirstBufferedUpdateId(),
                event != null ? Long.valueOf(event.firstUpdateId()) : state.getLastInputFirstUpdateId(),
                event != null ? Long.valueOf(event.finalUpdateId()) : state.getLastInputFinalUpdateId(),
                state.getBufferedEvents().size(),
                bestBid,
                bestAsk,
                ctx != null ? ctx.topic() : state.getLastProcessedTopic(),
                ctx != null ? ctx.key() : state.getLastProcessedKey(),
                ctx != null ? ctx.partition() : state.getLastProcessedPartition(),
                ctx != null ? ctx.offset() : state.getLastProcessedOffset(),
                stateAgeMs,
                state.getGapCount(),
                state.getResyncCount(),
                state.getDuplicateCount(),
                state.getSnapshotRetryCount());
    }

    private BookSyncStatus syncStatusFor(SymbolState state) {
        return switch (state.getStatus()) {
            case LIVE -> state.isTrusted() ? BookSyncStatus.IN_SYNC : BookSyncStatus.OUT_OF_SYNC;
            case INIT, BUFFERING_DIFFS, SNAPSHOT_LOADING, APPLYING_BUFFER -> BookSyncStatus.RECOVERING;
            case RESYNCING -> BookSyncStatus.OUT_OF_SYNC;
        };
    }

    private static long resolveTs(long stateTs, long eventTs, long now) {
        if (stateTs > 0) return stateTs;
        if (eventTs > 0) return eventTs;
        return now;
    }

    private static Long positiveOrNull(Long value) {
        return value != null && value >= 0 ? value : null;
    }
}
