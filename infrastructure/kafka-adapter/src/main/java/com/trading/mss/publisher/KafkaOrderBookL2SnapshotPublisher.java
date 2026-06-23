package com.trading.mss.publisher;

import com.trading.contracts.orderbook.OrderBookL2SnapshotEvent;
import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;
import com.trading.mss.mapper.OrderBookL2SnapshotAvroMapper;
import com.trading.mss.port.output.PublishOrderBookL2SnapshotPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@RequiredArgsConstructor
public class KafkaOrderBookL2SnapshotPublisher implements PublishOrderBookL2SnapshotPort {

    private final KafkaTemplate<String, OrderBookL2SnapshotEvent> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(OrderBookL2SnapshotDto dto) {
        OrderBookL2SnapshotEvent avro = OrderBookL2SnapshotAvroMapper.toAvro(dto);
        String instrumentId = avro.getMetadata().getInstrumentId();
        String key = resolveKey(instrumentId, avro.getMetadata().getSymbol());
        long stateSeq = avro.getVersion().getStateSeq();
        long exchangeUpdateId = avro.getVersion().getExchangeUpdateId();

        kafkaTemplate.send(topic, key, avro).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Publish OrderBookL2Snapshot FAILED: topic={} key={} instrumentId={} stateSeq={} exchangeUpdateId={}",
                        topic, key, instrumentId, stateSeq, exchangeUpdateId, ex);
            } else {
                var md = result.getRecordMetadata();
                log.debug("Published OrderBookL2Snapshot: topic={} key={} partition={} offset={} instrumentId={} stateSeq={} exchangeUpdateId={}",
                        topic, key, md.partition(), md.offset(), instrumentId, stateSeq, exchangeUpdateId);
            }
        });
    }

    private String resolveKey(String instrumentId, String symbol) {
        if (instrumentId == null || instrumentId.isBlank()) {
            log.warn("OrderBookL2Snapshot instrumentId is blank; falling back to symbol={} as Kafka key", symbol);
            return symbol;
        }
        return instrumentId;
    }
}
