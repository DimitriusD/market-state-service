package com.trading.mss.mapper;

import com.trading.contracts.common.PriceLevelEvent;
import com.trading.contracts.orderbook.BboProjectionEvent;
import com.trading.contracts.orderbook.OrderBookDepthProjectionEvent;
import com.trading.contracts.orderbook.OrderBookL2SnapshotEvent;
import com.trading.contracts.orderbook.OrderBookQualityEvent;
import com.trading.contracts.orderbook.OrderBookSourceEvent;
import com.trading.contracts.orderbook.OrderBookVersionEvent;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.orderbook.BboProjectionDto;
import com.trading.mss.dto.orderbook.OrderBookDepthProjectionDto;
import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;
import com.trading.mss.dto.orderbook.OrderBookQualityDto;
import com.trading.mss.dto.orderbook.OrderBookSourceDto;
import com.trading.mss.dto.orderbook.OrderBookVersionDto;

import java.util.List;

public final class OrderBookL2SnapshotAvroMapper {

    private OrderBookL2SnapshotAvroMapper() {}

    public static OrderBookL2SnapshotEvent toAvro(OrderBookL2SnapshotDto dto) {
        return new OrderBookL2SnapshotEvent(
                MetadataAvroMapper.toAvro(dto.metadata()),
                toAvro(dto.version()),
                toAvro(dto.quality()),
                toAvro(dto.bbo()),
                toAvro(dto.depth()),
                toAvro(dto.source()));
    }

    private static OrderBookVersionEvent toAvro(OrderBookVersionDto v) {
        return new OrderBookVersionEvent(
                v.stateSeq(),
                v.exchangeUpdateId(),
                v.previousExchangeUpdateId(),
                v.lastSnapshotUpdateId(),
                v.snapshotDepthLimit(),
                v.publishedDepth());
    }

    private static OrderBookQualityEvent toAvro(OrderBookQualityDto q) {
        return new OrderBookQualityEvent(
                OrderBookLifecycleStatusAvroMapper.toWire(q.lifecycleStatus()),
                BookSyncStatusAvroMapper.toWire(q.syncStatus()),
                q.trusted(),
                OrderBookReasonAvroMapper.toWire(q.reason()),
                q.snapshotDepthLimited(),
                q.stale(),
                q.crossed(),
                q.incompleteBook(),
                q.stateAgeMs(),
                q.publishLagMs(),
                q.bufferedDiffCount(),
                q.gapCount(),
                q.resyncCount(),
                q.duplicateCount(),
                q.snapshotRetryCount());
    }

    private static BboProjectionEvent toAvro(BboProjectionDto bbo) {
        if (bbo == null) {
            return null;
        }
        return new BboProjectionEvent(
                PriceLevelAvroMapper.toAvro(bbo.bestBid()),
                PriceLevelAvroMapper.toAvro(bbo.bestAsk()),
                bbo.spread(),
                bbo.mid());
    }

    private static OrderBookDepthProjectionEvent toAvro(OrderBookDepthProjectionDto depth) {
        return new OrderBookDepthProjectionEvent(
                toAvroLevels(depth.bids()),
                toAvroLevels(depth.asks()));
    }

    private static OrderBookSourceEvent toAvro(OrderBookSourceDto s) {
        return new OrderBookSourceEvent(
                s.inputTopic(),
                s.inputKey(),
                s.inputPartition(),
                s.inputOffset(),
                s.upstreamEventId(),
                s.inputFirstUpdateId(),
                s.inputFinalUpdateId());
    }

    private static List<PriceLevelEvent> toAvroLevels(List<PriceLevelDto> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.stream().map(PriceLevelAvroMapper::toAvro).toList();
    }
}
