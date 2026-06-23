package com.trading.mss.publisher;

import com.trading.contracts.orderbook.OrderBookStatusEvent;
import com.trading.mss.dto.orderbook.OrderBookStatusDto;
import com.trading.mss.mapper.OrderBookStatusAvroMapper;
import com.trading.mss.port.output.PublishOrderBookStatusPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@RequiredArgsConstructor
public class KafkaOrderBookStatusPublisher implements PublishOrderBookStatusPort {

    private final KafkaTemplate<String, OrderBookStatusEvent> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(OrderBookStatusDto dto) {
        OrderBookStatusEvent avro = OrderBookStatusAvroMapper.toAvro(dto);
        String instrumentId = avro.getMetadata().getInstrumentId();
        String key = resolveKey(instrumentId, avro.getMetadata().getSymbol());
        var lifecycleStatus = avro.getLifecycleStatus();
        var reason = avro.getReason();

        kafkaTemplate.send(topic, key, avro).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Publish OrderBookStatus FAILED: topic={} key={} instrumentId={} lifecycleStatus={} reason={}",
                        topic, key, instrumentId, lifecycleStatus, reason, ex);
            } else {
                var md = result.getRecordMetadata();
                log.debug("Published OrderBookStatus: topic={} key={} partition={} offset={} instrumentId={} lifecycleStatus={} reason={}",
                        topic, key, md.partition(), md.offset(), instrumentId, lifecycleStatus, reason);
            }
        });
    }

    private String resolveKey(String instrumentId, String symbol) {
        if (instrumentId == null || instrumentId.isBlank()) {
            log.warn("OrderBookStatus instrumentId is blank; falling back to symbol={} as Kafka key", symbol);
            return symbol;
        }
        return instrumentId;
    }
}
