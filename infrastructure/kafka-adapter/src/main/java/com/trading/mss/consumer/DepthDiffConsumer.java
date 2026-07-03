package com.trading.mss.consumer;

import com.trading.contracts.market.DepthDiffEvent;
import com.trading.mss.domain.model.SymbolKey;
import com.trading.mss.mapper.DepthDiffAvroMapper;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.input.DepthDiffProcessor;
import com.trading.mss.port.output.SymbolExecutorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Thin dispatcher: validates and deserializes the record on the listener thread, then routes the
 * command to the symbol's serialized executor. All state mutation happens inside that command.
 *
 * <p>Note on delivery semantics: the offset is committed after enqueue, not after processing.
 * Acceptable here — symbol state is in-memory only and rebuilt via snapshot bootstrap on restart;
 * a lost in-flight command surfaces as a sequence gap and triggers a resync.
 */
@Slf4j
@RequiredArgsConstructor
public class DepthDiffConsumer {

    private final DepthDiffProcessor processDepthDiff;
    private final SymbolExecutorPort symbolExecutor;

    @KafkaListener(
            topics = "${app.kafka.topic.depth-diff}",
            containerFactory = "depthDiffListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, DepthDiffEvent> record) {
        DepthDiffEvent value = record.value();
        if (value == null || value.getMetadata() == null
                || isBlank(value.getMetadata().getExchange()) || isBlank(value.getMetadata().getSymbol())) {
            log.warn("Skipping invalid depth diff record: topic={} partition={} offset={} key={} value={}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    value == null ? "null" : "metadata missing/blank");
            return;
        }

        var context = new KafkaMessageContext(record.topic(), record.key(), record.partition(), record.offset());
        var dto = DepthDiffAvroMapper.toDto(value);
        SymbolKey key = SymbolKey.of(dto.metadataDto());
        symbolExecutor.executorFor(key).execute(() -> processDepthDiff.process(dto, context));
    }

    private static boolean isBlank(CharSequence s) {
        return s == null || s.isEmpty();
    }
}
