package com.trading.mss.publisher;

import com.trading.contracts.orderbook.OrderBookL2SnapshotEvent;
import com.trading.mss.dto.orderbook.OrderBookL2SnapshotDto;
import com.trading.mss.mapper.OrderBookL2SnapshotAvroMapper;
import com.trading.mss.port.output.PublishOrderBookL2SnapshotPort;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaOrderBookL2SnapshotPublisher
        extends AbstractKafkaEventPublisher<OrderBookL2SnapshotEvent>
        implements PublishOrderBookL2SnapshotPort {

    public KafkaOrderBookL2SnapshotPublisher(
            KafkaTemplate<String, OrderBookL2SnapshotEvent> kafkaTemplate, String topic) {
        super(kafkaTemplate, topic, "OrderBookL2Snapshot",
                a -> a.getMetadata().getInstrumentId(),
                a -> a.getMetadata().getSymbol(),
                a -> "stateSeq=" + a.getVersion().getStateSeq()
                        + " exchangeUpdateId=" + a.getVersion().getExchangeUpdateId());
    }

    @Override
    public void publish(OrderBookL2SnapshotDto dto) {
        publishEvent(OrderBookL2SnapshotAvroMapper.toAvro(dto));
    }
}
