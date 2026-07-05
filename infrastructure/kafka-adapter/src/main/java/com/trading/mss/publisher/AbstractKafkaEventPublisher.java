package com.trading.mss.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.function.Function;

abstract class AbstractKafkaEventPublisher<A> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final KafkaTemplate<String, A> kafkaTemplate;
    private final String topic;
    private final String eventName;
    private final Function<A, String> instrumentIdExtractor;
    private final Function<A, String> symbolExtractor;
    private final Function<A, String> diagnosticsExtractor;

    protected AbstractKafkaEventPublisher(
            KafkaTemplate<String, A> kafkaTemplate,
            String topic,
            String eventName,
            Function<A, String> instrumentIdExtractor,
            Function<A, String> symbolExtractor,
            Function<A, String> diagnosticsExtractor) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.eventName = eventName;
        this.instrumentIdExtractor = instrumentIdExtractor;
        this.symbolExtractor = symbolExtractor;
        this.diagnosticsExtractor = diagnosticsExtractor;
    }

    protected final void publishEvent(A avro) {
        String instrumentId = instrumentIdExtractor.apply(avro);
        if (instrumentId == null || instrumentId.isBlank()) {
            log.error("Skipping publish: {} instrumentId is blank, symbol={}",
                    eventName, symbolExtractor.apply(avro));
            return;
        }
        String diagnostics = diagnosticsExtractor.apply(avro);

        kafkaTemplate.send(topic, instrumentId, avro).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Publish {} FAILED: topic={} key={} instrumentId={} {}",
                        eventName, topic, instrumentId, instrumentId, diagnostics, ex);
            } else {
                var md = result.getRecordMetadata();
                log.debug("Published {}: topic={} key={} partition={} offset={} instrumentId={} {}",
                        eventName, topic, instrumentId, md.partition(), md.offset(), instrumentId, diagnostics);
            }
        });
    }
}
