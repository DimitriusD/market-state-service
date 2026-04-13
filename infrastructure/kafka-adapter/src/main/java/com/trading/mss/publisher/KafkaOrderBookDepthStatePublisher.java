package com.trading.mss.publisher;

import com.trading.mss.dto.orderbook.OrderBookDepthStateDto;
import com.trading.mss.mapper.OrderBookDepthStateAvroMapper;
import com.trading.mss.message.outbound.OrderBookDepthStateEvent;
import com.trading.mss.port.output.PublishOrderBookDepthStatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@RequiredArgsConstructor
public class KafkaOrderBookDepthStatePublisher implements PublishOrderBookDepthStatePort {

    private final KafkaTemplate<String, OrderBookDepthStateEvent> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(OrderBookDepthStateDto event) {
        String key = event.metadata().symbol();
        OrderBookDepthStateEvent avro = OrderBookDepthStateAvroMapper.toAvro(event);
        kafkaTemplate.send(topic, key, avro);
        log.debug(
                "Published OrderBook depth state (Avro): symbol={} publishedLevels={} topic={}",
                key,
                event.publishedLevels(),
                topic);
    }
}
