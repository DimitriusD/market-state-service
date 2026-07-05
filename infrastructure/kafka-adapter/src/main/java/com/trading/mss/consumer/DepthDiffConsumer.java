package com.trading.mss.consumer;

import com.trading.contracts.market.DepthDiffEvent;
import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.mapper.DepthDiffAvroMapper;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.input.DepthDiffProcessor;
import com.trading.mss.port.output.SymbolExecutorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Slf4j
@RequiredArgsConstructor
public class DepthDiffConsumer {

    private final DepthDiffProcessor processDepthDiff;
    private final SymbolExecutorPort symbolExecutor;

    @KafkaListener(
            topics = "${app.kafka.topics.depth-diff}",
            containerFactory = "depthDiffListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, DepthDiffEvent> depthDiffEvent) {
        var context = new KafkaMessageContext(depthDiffEvent.topic(), depthDiffEvent.key(), depthDiffEvent.partition(), depthDiffEvent.offset());
        var dto = DepthDiffAvroMapper.toDto(depthDiffEvent.value());
        var key = InstrumentKey.of(dto.metadataDto());
        symbolExecutor.executorFor(key).execute(() -> processDepthDiff.process(dto, context));
    }
}
