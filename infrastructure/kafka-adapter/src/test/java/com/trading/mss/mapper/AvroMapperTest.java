package com.trading.mss.mapper;

import com.trading.common.enums.BookSyncStatus;
import com.trading.contracts.orderbook.OrderBookL2SnapshotEvent;
import com.trading.contracts.orderbook.OrderBookLifecycleStatus;
import com.trading.contracts.orderbook.OrderBookReason;
import com.trading.contracts.orderbook.OrderBookStatusEvent;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.orderbook.BboProjectionDto;
import com.trading.mss.dto.orderbook.OrderBookDepthProjectionDto;
import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;
import com.trading.mss.dto.orderbook.OrderBookQualityDto;
import com.trading.mss.dto.orderbook.OrderBookSourceDto;
import com.trading.mss.dto.orderbook.OrderBookStatusDetailsDto;
import com.trading.mss.dto.orderbook.OrderBookStatusDto;
import com.trading.mss.dto.orderbook.OrderBookVersionDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AvroMapperTest {

    @Test
    void snapshotDto_mapsToAvroWithoutNullOrDefaultErrors() {
        OrderBookL2SnapshotDto dto = new OrderBookL2SnapshotDto(
                metadata("ORDERBOOK_L2_SNAPSHOT"),
                new OrderBookVersionDto(7, 105, 104L, 100, 1000, 10),
                new OrderBookQualityDto(
                        SymbolStateStatus.LIVE, BookSyncStatus.IN_SYNC, true,
                        com.trading.mss.domain.model.OrderBookReason.NONE,
                        true, false, false, false, 12, 12, 3, 1, 2, 4, 5),
                new BboProjectionDto(
                        new PriceLevelDto("50000.00000000", "1.00000000"),
                        new PriceLevelDto("50001.00000000", "2.00000000"),
                        "1.00000000", "50000.50000000"),
                new OrderBookDepthProjectionDto(
                        List.of(new PriceLevelDto("50000.00000000", "1.00000000")),
                        List.of(new PriceLevelDto("50001.00000000", "2.00000000"))),
                new OrderBookSourceDto(
                        "canonical.market.depthdiff.v1", "BTCUSDT", 3, 42, "evt-1", 106L, 110L));

        OrderBookL2SnapshotEvent avro = OrderBookL2SnapshotAvroMapper.toAvro(dto);

        assertEquals("BTCUSDT", avro.getMetadata().getInstrumentId());
        assertEquals(7L, avro.getVersion().getStateSeq());
        assertEquals(105L, avro.getVersion().getExchangeUpdateId());
        assertEquals(104L, avro.getVersion().getPreviousExchangeUpdateId());
        assertEquals(OrderBookLifecycleStatus.LIVE, avro.getQuality().getLifecycleStatus());
        assertEquals(com.trading.contracts.orderbook.BookSyncStatus.IN_SYNC, avro.getQuality().getSyncStatus());
        assertEquals(OrderBookReason.NONE, avro.getQuality().getReason());
        assertNotNull(avro.getBbo());
        assertEquals("50000.00000000", avro.getBbo().getBestBid().getPrice());
        assertEquals(1, avro.getDepth().getBids().size());
        assertEquals(1, avro.getDepth().getAsks().size());
        assertEquals("canonical.market.depthdiff.v1", avro.getSource().getInputTopic());
        assertEquals(42L, avro.getSource().getInputOffset());
        assertEquals(106L, avro.getSource().getInputFirstUpdateId());
    }

    @Test
    void statusDto_mapsToAvroWithNullableFields() {
        OrderBookStatusDetailsDto details = new OrderBookStatusDetailsDto(
                105, null, 100, null, null, null, 0,
                null, null, null, null, -1, -1, -1, 0, 1, 0, 1);

        OrderBookStatusDto dto = new OrderBookStatusDto(
                metadata("ORDERBOOK_STATUS"),
                SymbolStateStatus.RESYNCING,
                BookSyncStatus.OUT_OF_SYNC,
                false,
                com.trading.mss.domain.model.OrderBookReason.CROSSED_BOOK,
                details,
                "crossed book detected");

        OrderBookStatusEvent avro = OrderBookStatusAvroMapper.toAvro(dto);

        assertEquals(OrderBookLifecycleStatus.RESYNCING, avro.getLifecycleStatus());
        assertEquals(com.trading.contracts.orderbook.BookSyncStatus.OUT_OF_SYNC, avro.getSyncStatus());
        assertFalse(avro.getTrusted());
        assertEquals(OrderBookReason.CROSSED_BOOK, avro.getReason());
        assertEquals("crossed book detected", avro.getMessage());
        assertEquals(105L, avro.getDetails().getLocalUpdateId());
        assertNull(avro.getDetails().getPreviousLocalUpdateId());
        assertNull(avro.getDetails().getBestBid());
        assertEquals(-1, avro.getDetails().getInputPartition());
    }

    private static MetadataDto metadata(String eventType) {
        return new MetadataDto(1, eventType, "binance", "spot", "BTC", "USDT",
                "BTCUSDT", "BTCUSDT", "evt-1", "market-state-service",
                1_700_000_000_000L, 1_700_000_000_001L, 1_700_000_000_002L);
    }
}
