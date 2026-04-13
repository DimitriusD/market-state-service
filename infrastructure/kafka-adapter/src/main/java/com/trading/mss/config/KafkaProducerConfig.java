package com.trading.mss.config;

import com.trading.contracts.orderbook.BboStateEvent;
import com.trading.contracts.orderbook.OrderBookDepthStateEvent;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookDepthStatePort;
import com.trading.mss.publisher.KafkaBboStatePublisher;
import com.trading.mss.publisher.KafkaOrderBookDepthStatePublisher;
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

    private static Map<String, Object> avroProducerConfig(
            String bootstrapServers, String schemaRegistryUrl, boolean autoRegisterSchemas) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        config.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, autoRegisterSchemas);
        return config;
    }

    @Bean
    public ProducerFactory<String, BboStateEvent> bboStateAvroProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.schema-registry.url:http://localhost:8081}") String schemaRegistryUrl,
            @Value("${app.kafka.schema-registry.auto-register-schemas:true}") boolean autoRegisterSchemas) {
        return new DefaultKafkaProducerFactory<>(
                avroProducerConfig(bootstrapServers, schemaRegistryUrl, autoRegisterSchemas));
    }

    @Bean
    public KafkaTemplate<String, BboStateEvent> bboStateKafkaTemplate(
            ProducerFactory<String, BboStateEvent> bboStateAvroProducerFactory) {
        return new KafkaTemplate<>(bboStateAvroProducerFactory);
    }

    @Bean
    public ProducerFactory<String, OrderBookDepthStateEvent> orderBookDepthStateAvroProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.schema-registry.url:http://localhost:8081}") String schemaRegistryUrl,
            @Value("${app.kafka.schema-registry.auto-register-schemas:true}") boolean autoRegisterSchemas) {
        return new DefaultKafkaProducerFactory<>(
                avroProducerConfig(bootstrapServers, schemaRegistryUrl, autoRegisterSchemas));
    }

    @Bean
    public KafkaTemplate<String, OrderBookDepthStateEvent> orderBookDepthStateKafkaTemplate(
            ProducerFactory<String, OrderBookDepthStateEvent> orderBookDepthStateAvroProducerFactory) {
        return new KafkaTemplate<>(orderBookDepthStateAvroProducerFactory);
    }

    @Bean
    public PublishBboStatePort publishBboStatePort(
            KafkaTemplate<String, BboStateEvent> bboStateKafkaTemplate,
            @Value("${app.kafka.topics.bbo-state:state.bbo.v1}") String topic) {
        return new KafkaBboStatePublisher(bboStateKafkaTemplate, topic);
    }

    @Bean
    public PublishOrderBookDepthStatePort publishOrderBookDepthStatePort(
            KafkaTemplate<String, OrderBookDepthStateEvent> orderBookDepthStateKafkaTemplate,
            @Value("${app.kafka.topics.orderbook-topn-state:state.orderbook.l2.topn.v1}") String topic) {
        return new KafkaOrderBookDepthStatePublisher(orderBookDepthStateKafkaTemplate, topic);
    }
}
