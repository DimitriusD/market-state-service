package com.trading.mss.publisher;

import com.trading.contracts.orderbook.OrderBookStatusEvent;
import com.trading.mss.dto.orderbook.OrderBookStatusDto;
import com.trading.mss.mapper.OrderBookStatusAvroMapper;
import com.trading.mss.port.output.PublishOrderBookStatusPort;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaOrderBookStatusPublisher
        extends AbstractKafkaEventPublisher<OrderBookStatusEvent>
        implements PublishOrderBookStatusPort {

    public KafkaOrderBookStatusPublisher(
            KafkaTemplate<String, OrderBookStatusEvent> kafkaTemplate, String topic) {
        super(kafkaTemplate, topic, "OrderBookStatus",
                a -> a.getMetadata().getInstrumentId(),
                a -> a.getMetadata().getSymbol(),
                a -> "lifecycleStatus=" + a.getLifecycleStatus()
                        + " reason=" + a.getReason());
    }

    @Override
    public void publish(OrderBookStatusDto dto) {
        publishEvent(OrderBookStatusAvroMapper.toAvro(dto));
    }
}
