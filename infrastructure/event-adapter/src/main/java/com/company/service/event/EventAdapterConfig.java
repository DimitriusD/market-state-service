package com.company.service.event;

import com.company.service.application.port.output.ItemEventPublisherPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class EventAdapterConfig {

    @Bean
    public ProducerFactory<String, ItemEvent> itemEventProducerFactory(
            ObjectMapper objectMapper,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new JsonSerializer<>(objectMapper));
    }

    @Bean
    public KafkaTemplate<String, ItemEvent> itemEventKafkaTemplate(
            ProducerFactory<String, ItemEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ItemEventPublisherPort kafkaItemEventPublisher(
            KafkaTemplate<String, ItemEvent> kafkaTemplate,
            @Value("${app.kafka.topic.item-events:service.item.events.v1}") String topic) {
        return new KafkaItemEventPublisher(kafkaTemplate, topic);
    }
}
