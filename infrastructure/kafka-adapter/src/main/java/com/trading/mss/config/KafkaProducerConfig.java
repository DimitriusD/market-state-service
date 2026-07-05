package com.trading.mss.config;

import com.trading.contracts.orderbook.OrderBookL2SnapshotEvent;
import com.trading.contracts.orderbook.OrderBookStatusEvent;
import com.trading.mss.port.output.PublishOrderBookL2SnapshotPort;
import com.trading.mss.port.output.PublishOrderBookStatusPort;
import com.trading.mss.publisher.KafkaOrderBookL2SnapshotPublisher;
import com.trading.mss.publisher.KafkaOrderBookStatusPublisher;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    private <T> ProducerFactory<String, T> avroProducerFactory(
            String bootstrapServers, String schemaRegistryUrl, boolean autoRegisterSchemas) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        config.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, autoRegisterSchemas);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public ProducerFactory<String, OrderBookL2SnapshotEvent> orderBookL2SnapshotAvroProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.schema-registry.url:http://localhost:8081}") String schemaRegistryUrl,
            @Value("${app.kafka.schema-registry.auto-register-schemas:true}") boolean autoRegisterSchemas) {
        return avroProducerFactory(bootstrapServers, schemaRegistryUrl, autoRegisterSchemas);
    }

    @Bean
    public KafkaTemplate<String, OrderBookL2SnapshotEvent> orderBookL2SnapshotKafkaTemplate(
            ProducerFactory<String, OrderBookL2SnapshotEvent> orderBookL2SnapshotAvroProducerFactory) {
        return new KafkaTemplate<>(orderBookL2SnapshotAvroProducerFactory);
    }

    @Bean
    public ProducerFactory<String, OrderBookStatusEvent> orderBookStatusAvroProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.schema-registry.url:http://localhost:8081}") String schemaRegistryUrl,
            @Value("${app.kafka.schema-registry.auto-register-schemas:true}") boolean autoRegisterSchemas) {
        return avroProducerFactory(bootstrapServers, schemaRegistryUrl, autoRegisterSchemas);
    }

    @Bean
    public KafkaTemplate<String, OrderBookStatusEvent> orderBookStatusKafkaTemplate(
            ProducerFactory<String, OrderBookStatusEvent> orderBookStatusAvroProducerFactory) {
        return new KafkaTemplate<>(orderBookStatusAvroProducerFactory);
    }

    @Bean
    public PublishOrderBookL2SnapshotPort publishOrderBookL2SnapshotPort(
            KafkaTemplate<String, OrderBookL2SnapshotEvent> orderBookL2SnapshotKafkaTemplate,
            @Value("${app.kafka.topics.orderbook-l2-snapshot:market.state.orderbook.l2.snapshot.v1}") String topic) {
        return new KafkaOrderBookL2SnapshotPublisher(orderBookL2SnapshotKafkaTemplate, topic);
    }

    @Bean
    public PublishOrderBookStatusPort publishOrderBookStatusPort(
            KafkaTemplate<String, OrderBookStatusEvent> orderBookStatusKafkaTemplate,
            @Value("${app.kafka.topics.orderbook-status:market.state.orderbook.status.v1}") String topic) {
        return new KafkaOrderBookStatusPublisher(orderBookStatusKafkaTemplate, topic);
    }
}
