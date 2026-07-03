package com.trading.mss.publisher;

import com.trading.common.enums.BookSyncStatus;
import com.trading.contracts.orderbook.OrderBookL2SnapshotEvent;
import com.trading.contracts.orderbook.OrderBookStatusEvent;
import com.trading.mss.domain.model.OrderBookReason;
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
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class KafkaPublisherConfigTest {

    private static final String DEFAULT_SNAPSHOT_TOPIC = "market.state.orderbook.l2.snapshot.v1";
    private static final String DEFAULT_STATUS_TOPIC = "market.state.orderbook.status.v1";
    private static final String INSTRUMENT_ID = "BINANCE|SPOT|BTC|USDT";

    @Test
    void snapshotPublisher_sendsToConfiguredTopicKeyedByInstrumentId() {
        RecordingKafkaTemplate<OrderBookL2SnapshotEvent> template = new RecordingKafkaTemplate<>();
        var publisher = new KafkaOrderBookL2SnapshotPublisher(template, DEFAULT_SNAPSHOT_TOPIC);

        publisher.publish(snapshotDto(INSTRUMENT_ID));

        assertEquals(DEFAULT_SNAPSHOT_TOPIC, template.topic);
        assertEquals(INSTRUMENT_ID, template.key);
    }

    @Test
    void snapshotPublisher_skipsWhenInstrumentIdBlank() {
        RecordingKafkaTemplate<OrderBookL2SnapshotEvent> template = new RecordingKafkaTemplate<>();
        var publisher = new KafkaOrderBookL2SnapshotPublisher(template, DEFAULT_SNAPSHOT_TOPIC);

        publisher.publish(snapshotDto(""));

        assertNull(template.topic, "blank instrumentId must skip the publish, never fall back to symbol");
        assertNull(template.key);
    }

    @Test
    void statusPublisher_sendsToConfiguredTopicKeyedByInstrumentId() {
        RecordingKafkaTemplate<OrderBookStatusEvent> template = new RecordingKafkaTemplate<>();
        var publisher = new KafkaOrderBookStatusPublisher(template, DEFAULT_STATUS_TOPIC);

        publisher.publish(statusDto(INSTRUMENT_ID));

        assertEquals(DEFAULT_STATUS_TOPIC, template.topic);
        assertEquals(INSTRUMENT_ID, template.key);
    }

    @Test
    void statusPublisher_skipsWhenInstrumentIdBlank() {
        RecordingKafkaTemplate<OrderBookStatusEvent> template = new RecordingKafkaTemplate<>();
        var publisher = new KafkaOrderBookStatusPublisher(template, DEFAULT_STATUS_TOPIC);

        publisher.publish(statusDto(""));

        assertNull(template.topic, "blank instrumentId must skip the publish, never fall back to symbol");
        assertNull(template.key);
    }

    private static OrderBookL2SnapshotDto snapshotDto(String instrumentId) {
        return new OrderBookL2SnapshotDto(
                metadata("ORDERBOOK_L2_SNAPSHOT", instrumentId),
                new OrderBookVersionDto(1, 105, null, 100, 1000, 10),
                new OrderBookQualityDto(SymbolStateStatus.LIVE, BookSyncStatus.IN_SYNC, true,
                        OrderBookReason.NONE, true, false, false, false, 0, 0, 0, 0, 0, 0, 0),
                new BboProjectionDto(
                        new PriceLevelDto("50000.00000000", "1.00000000"),
                        new PriceLevelDto("50001.00000000", "1.00000000"),
                        "1.00000000", "50000.50000000"),
                new OrderBookDepthProjectionDto(
                        List.of(new PriceLevelDto("50000.00000000", "1.00000000")),
                        List.of(new PriceLevelDto("50001.00000000", "1.00000000"))),
                new OrderBookSourceDto("canonical.market.depthdiff.v1", "BTCUSDT", 0, 1, "evt-1", 104L, 105L));
    }

    private static OrderBookStatusDto statusDto(String instrumentId) {
        OrderBookStatusDetailsDto details = new OrderBookStatusDetailsDto(
                105, null, 100, null, null, null, 0, null, null, null, null, -1, -1, -1, 0, 1, 0, 1);
        return new OrderBookStatusDto(
                metadata("ORDERBOOK_STATUS", instrumentId),
                SymbolStateStatus.RESYNCING, BookSyncStatus.OUT_OF_SYNC, false,
                OrderBookReason.GAP_DETECTED, details, "gap");
    }

    private static MetadataDto metadata(String eventType, String instrumentId) {
        return new MetadataDto(1, eventType, "binance", "spot", "BTC", "USDT",
                "BTCUSDT", instrumentId, "evt-1", "market-state-service",
                1_700_000_000_000L, 1_700_000_000_001L, 1_700_000_000_002L);
    }

    /** Records the (topic, key) of a send and never completes the future, so callbacks don't fire. */
    static class RecordingKafkaTemplate<V> extends KafkaTemplate<String, V> {
        String topic;
        String key;
        V value;

        RecordingKafkaTemplate() {
            super(new DefaultKafkaProducerFactory<>(new HashMap<>()));
        }

        @Override
        public CompletableFuture<SendResult<String, V>> send(String topic, String key, V data) {
            this.topic = topic;
            this.key = key;
            this.value = data;
            return new CompletableFuture<>();
        }
    }
}
