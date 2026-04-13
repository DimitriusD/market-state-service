package com.trading.mss.publisher;

import com.trading.contracts.orderbook.BboStateEvent;
import com.trading.mss.dto.orderbook.BboStateDto;
import com.trading.mss.mapper.BboStateAvroMapper;
import com.trading.mss.port.output.PublishBboStatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@RequiredArgsConstructor
public class KafkaBboStatePublisher implements PublishBboStatePort {

    private final KafkaTemplate<String, BboStateEvent> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(BboStateDto event) {
        String key = event.metadata().symbol();
        BboStateEvent avro = BboStateAvroMapper.toAvro(event);
        kafkaTemplate.send(topic, key, avro);
        log.debug("Published BBO state (Avro): symbol={} topic={}", key, topic);
    }
}
