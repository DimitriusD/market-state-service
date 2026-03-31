package com.trading.mss.publisher;

import com.trading.mss.message.outbound.BboStateEvent;
import com.trading.mss.port.output.PublishBboStatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@RequiredArgsConstructor
public class KafkaBboStatePublisher implements PublishBboStatePort {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(BboStateEvent event) {
        String key = event.metadata().symbol();
        kafkaTemplate.send(topic, key, event);
        log.debug("Published BBO state: symbol={} topic={}", key, topic);
    }
}
