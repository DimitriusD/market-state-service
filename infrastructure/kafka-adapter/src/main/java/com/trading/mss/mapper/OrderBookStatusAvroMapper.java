package com.trading.mss.mapper;

import com.trading.contracts.orderbook.OrderBookStatusDetailsEvent;
import com.trading.contracts.orderbook.OrderBookStatusEvent;
import com.trading.mss.dto.orderbook.OrderBookStatusDetailsDto;
import com.trading.mss.dto.orderbook.OrderBookStatusDto;

public final class OrderBookStatusAvroMapper {

    private OrderBookStatusAvroMapper() {}

    public static OrderBookStatusEvent toAvro(OrderBookStatusDto dto) {
        return new OrderBookStatusEvent(
                MetadataAvroMapper.toAvro(dto.metadata()),
                OrderBookLifecycleStatusAvroMapper.toWire(dto.lifecycleStatus()),
                BookSyncStatusAvroMapper.toWire(dto.syncStatus()),
                dto.trusted(),
                OrderBookReasonAvroMapper.toWire(dto.reason()),
                toAvro(dto.details()),
                dto.message());
    }

    private static OrderBookStatusDetailsEvent toAvro(OrderBookStatusDetailsDto d) {
        return new OrderBookStatusDetailsEvent(
                d.localUpdateId(),
                d.previousLocalUpdateId(),
                d.lastSnapshotUpdateId(),
                d.firstBufferedUpdateId(),
                d.lastEventFirstUpdateId(),
                d.lastEventFinalUpdateId(),
                d.bufferedDiffCount(),
                PriceLevelAvroMapper.toAvro(d.bestBid()),
                PriceLevelAvroMapper.toAvro(d.bestAsk()),
                d.inputTopic(),
                d.inputKey(),
                d.inputPartition(),
                d.inputOffset(),
                d.stateAgeMs(),
                d.gapCount(),
                d.resyncCount(),
                d.duplicateCount(),
                d.snapshotRetryCount());
    }
}
