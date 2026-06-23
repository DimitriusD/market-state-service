package com.trading.mss.mapper;

import com.trading.common.enums.BookSyncStatus;
import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.orderbook.BboProjectionDto;
import com.trading.mss.dto.orderbook.OrderBookDepthProjectionDto;
import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;
import com.trading.mss.dto.orderbook.OrderBookQualityDto;
import com.trading.mss.dto.orderbook.OrderBookSourceDto;
import com.trading.mss.dto.orderbook.OrderBookVersionDto;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * Projects a trusted, in-sync {@link SymbolState} into a single {@link OrderBookL2SnapshotDto}
 * carrying BBO and top-N depth from the same reconstructed book version.
 *
 * <p>Returns {@link Optional#empty()} (and never consumes a {@code stateSeq}) when the book must not
 * be published as trusted market state: not LIVE, not trusted, crossed, or one-sided.
 */
@RequiredArgsConstructor
public class OrderBookL2SnapshotMapper {

    private static final String EVENT_TYPE = "ORDERBOOK_L2_SNAPSHOT";

    private final Clock clock;

    public Optional<OrderBookL2SnapshotDto> project(
            SymbolState state,
            DepthDiffDto triggeringEvent,
            KafkaMessageContext ctx,
            int publishedDepth,
            int snapshotDepthLimit) {

        if (state.getStatus() != SymbolStateStatus.LIVE) {
            return Optional.empty();
        }
        if (!state.isTrusted()) {
            return Optional.empty();
        }
        OrderBook book = state.getOrderBook();
        if (book.isCrossed()) {
            return Optional.empty();
        }
        // MVP: never publish a trusted snapshot with an incomplete (one-sided) book.
        if (!book.hasBids() || !book.hasAsks()) {
            return Optional.empty();
        }

        long now = clock.millis();
        long exchangeTs = state.getLastEventExchangeTs();
        long stateAgeMs = exchangeTs > 0 ? now - exchangeTs : 0;

        long stateSeq = state.nextStateSeq();

        MetadataDto metadata = StateMetadataFactory.build(
                state, EVENT_TYPE,
                "mss-orderbook-l2-snapshot-" + StateMetadataFactory.safeInstrumentId(state) + "-" + stateSeq,
                exchangeTs, state.getLastEventReceivedTs(), now);

        OrderBookVersionDto version = new OrderBookVersionDto(
                stateSeq,
                state.getLocalUpdateId(),
                positiveOrNull(state.getPreviousLocalUpdateId()),
                state.getLastSnapshotUpdateId(),
                snapshotDepthLimit,
                publishedDepth);

        OrderBookQualityDto quality = new OrderBookQualityDto(
                SymbolStateStatus.LIVE,
                BookSyncStatus.IN_SYNC,
                true,
                OrderBookReason.NONE,
                true,   // snapshotDepthLimited: local book built from a depth-limited snapshot
                false,  // stale (MVP: no freshness SLA yet)
                false,  // crossed (guarded above)
                false,  // incompleteBook (guarded above)
                stateAgeMs,
                stateAgeMs,  // publishLagMs == stateAgeMs for MVP
                state.getBufferedEvents().size(),
                state.getGapCount(),
                state.getResyncCount(),
                state.getDuplicateCount(),
                state.getSnapshotRetryCount());

        BboProjectionDto bbo = buildBbo(book);
        OrderBookDepthProjectionDto depth = new OrderBookDepthProjectionDto(
                projectLevels(book.getBids(), publishedDepth),
                projectLevels(book.getAsks(), publishedDepth));
        OrderBookSourceDto source = buildSource(state, triggeringEvent, ctx);

        return Optional.of(new OrderBookL2SnapshotDto(metadata, version, quality, bbo, depth, source));
    }

    private BboProjectionDto buildBbo(OrderBook book) {
        long bestBidPrice = book.bestBid();
        long bestAskPrice = book.bestAsk();

        PriceLevelDto bestBid = new PriceLevelDto(
                ScaledDecimal.format(bestBidPrice),
                ScaledDecimal.format(book.getBids().get(bestBidPrice)));
        PriceLevelDto bestAsk = new PriceLevelDto(
                ScaledDecimal.format(bestAskPrice),
                ScaledDecimal.format(book.getAsks().get(bestAskPrice)));

        String spread = ScaledDecimal.format(bestAskPrice - bestBidPrice);
        String mid = BigDecimal.valueOf(bestBidPrice, ScaledDecimal.SCALE_DIGITS)
                .add(BigDecimal.valueOf(bestAskPrice, ScaledDecimal.SCALE_DIGITS))
                .divide(BigDecimal.valueOf(2), ScaledDecimal.SCALE_DIGITS, RoundingMode.HALF_UP)
                .toPlainString();

        return new BboProjectionDto(bestBid, bestAsk, spread, mid);
    }

    private OrderBookSourceDto buildSource(SymbolState state, DepthDiffDto triggeringEvent, KafkaMessageContext ctx) {
        String inputTopic = ctx != null ? ctx.topic() : state.getLastProcessedTopic();
        String inputKey = ctx != null ? ctx.key() : state.getLastProcessedKey();
        int inputPartition = ctx != null ? ctx.partition() : state.getLastProcessedPartition();
        long inputOffset = ctx != null ? ctx.offset() : state.getLastProcessedOffset();

        String upstreamEventId = triggeringEvent != null && triggeringEvent.metadataDto() != null
                ? triggeringEvent.metadataDto().eventId() : null;
        Long inputFirstUpdateId = triggeringEvent != null
                ? Long.valueOf(triggeringEvent.firstUpdateId()) : state.getLastInputFirstUpdateId();
        Long inputFinalUpdateId = triggeringEvent != null
                ? Long.valueOf(triggeringEvent.finalUpdateId()) : state.getLastInputFinalUpdateId();

        return new OrderBookSourceDto(
                inputTopic, inputKey, inputPartition, inputOffset,
                upstreamEventId, inputFirstUpdateId, inputFinalUpdateId);
    }

    private List<PriceLevelDto> projectLevels(NavigableMap<Long, Long> side, int publishedDepth) {
        List<PriceLevelDto> result = new ArrayList<>(Math.min(publishedDepth, side.size()));
        for (Map.Entry<Long, Long> entry : side.entrySet()) {
            if (result.size() >= publishedDepth) break;
            if (entry.getValue() == 0) continue;  // never publish zero-qty levels
            result.add(new PriceLevelDto(
                    ScaledDecimal.format(entry.getKey()),
                    ScaledDecimal.format(entry.getValue())));
        }
        return result;
    }

    private static Long positiveOrNull(Long value) {
        return value != null && value >= 0 ? value : null;
    }
}
