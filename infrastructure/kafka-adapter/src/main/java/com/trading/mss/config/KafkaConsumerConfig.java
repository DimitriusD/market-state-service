package com.trading.mss.config;

import com.trading.contracts.market.DepthDiffEvent;
import com.trading.mss.consumer.DepthDiffConsumer;
import com.trading.mss.port.input.DepthDiffProcessor;
import com.trading.mss.port.output.SymbolExecutorPort;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, DepthDiffEvent> depthDiffConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId,
            @Value("${app.kafka.schema-registry.url:http://localhost:8081}") String schemaRegistryUrl) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Wrap the Avro deserializer so a poison message (bad schema, garbage bytes, Schema Registry
        // miss) surfaces as a DeserializationException the DefaultErrorHandler can skip, instead of
        // being thrown inside poll() and looping the consumer on one offset forever.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DepthDiffEvent> depthDiffListenerContainerFactory(
            ConsumerFactory<String, DepthDiffEvent> depthDiffConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DepthDiffEvent>();
        factory.setConsumerFactory(depthDiffConsumerFactory);
        factory.setCommonErrorHandler(depthDiffErrorHandler());
        return factory;
    }

    /**
     * DefaultErrorHandler treats DeserializationException as non-retryable, so a poison record goes
     * straight to the recoverer: we log it and let the container seek past the offset (skip).
     */
    private DefaultErrorHandler depthDiffErrorHandler() {
        return new DefaultErrorHandler((record, exception) ->
                log.error("Skipping poison record on topic={} partition={} offset={}: {}",
                        record.topic(), record.partition(), record.offset(), exception.getMessage(), exception));
    }

    @Bean
    public DepthDiffConsumer depthDiffConsumer(DepthDiffProcessor processDepthDiff,
                                               SymbolExecutorPort symbolExecutorPort) {
        return new DepthDiffConsumer(processDepthDiff, symbolExecutorPort);
    }
}
