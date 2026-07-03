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
        String invalidReason = validate(record.value());
        if (invalidReason != null) {
            log.warn("Skipping invalid depth diff record: topic={} partition={} offset={} key={} reason={}",
                    record.topic(), record.partition(), record.offset(), record.key(), invalidReason);
            return;
        }

        var context = new KafkaMessageContext(record.topic(), record.key(), record.partition(), record.offset());
        var dto = DepthDiffAvroMapper.toDto(record.value());
        // Identity comes from metadata.instrumentId — the Kafka record key is diagnostics only.
        InstrumentKey key = InstrumentKey.of(dto.metadataDto());
        symbolExecutor.executorFor(key).execute(() -> processDepthDiff.process(dto, context));
    }

    /** Returns null when valid, otherwise the reason the record must be skipped. */
    private static String validate(DepthDiffEvent value) {
        if (value == null) {
            return "null value (tombstone)";
        }
        var metadata = value.getMetadata();
        if (metadata == null) {
            return "metadata missing";
        }
        if (isBlank(metadata.getInstrumentId())) {
            return "blank instrumentId";
        }
        if (isBlank(metadata.getExchange())) {
            return "blank exchange";
        }
        if (isBlank(metadata.getMarketType())) {
            return "blank marketType";
        }
        if (isBlank(metadata.getSymbol())) {
            return "blank symbol";
        }
        return null;
    }

    private static boolean isBlank(CharSequence s) {
        return s == null || s.toString().isBlank();
    }
}
