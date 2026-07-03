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
        // instrumentId is the only valid output key — no fallback identity mode. A blank id here
        // means an upstream invariant is broken; skip rather than publish under a wrong key.
        if (instrumentId == null || instrumentId.isBlank()) {
            log.error("Skipping publish: OrderBookStatus instrumentId is blank, symbol={}",
                    avro.getMetadata().getSymbol());
            return;
        }
        String key = instrumentId;
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

}
