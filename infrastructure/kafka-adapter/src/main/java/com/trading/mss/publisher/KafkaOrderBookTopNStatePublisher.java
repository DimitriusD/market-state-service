package com.trading.mss.publisher;

import com.trading.mss.message.outbound.OrderBookTopNStateEvent;
import com.trading.mss.port.output.PublishOrderBookTopNStatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@RequiredArgsConstructor
public class KafkaOrderBookTopNStatePublisher implements PublishOrderBookTopNStatePort {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(OrderBookTopNStateEvent event) {
        String key = event.metadata().symbol();
        kafkaTemplate.send(topic, key, event);
        log.debug("Published OrderBook TopN state: symbol={} depth={} topic={}", key, event.depth(), topic);
    }
}
