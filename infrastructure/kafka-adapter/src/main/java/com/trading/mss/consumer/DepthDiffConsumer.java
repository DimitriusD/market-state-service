package com.trading.mss.consumer;

import com.trading.contracts.market.DepthDiffEvent;
import com.trading.mss.mapper.DepthDiffAvroMapper;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@RequiredArgsConstructor
public class DepthDiffConsumer {

    private final ProcessDepthDiffUseCase processDepthDiff;

    @KafkaListener(
            topics = "${app.kafka.topic.depth-diff}",
            containerFactory = "depthDiffListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, DepthDiffEvent> depthDiffEvent) {
        var context = new KafkaMessageContext(depthDiffEvent.key(), depthDiffEvent.partition(), depthDiffEvent.offset());
        var dto = DepthDiffAvroMapper.toDto(depthDiffEvent.value());
        processDepthDiff.process(dto, context);
    }
}
