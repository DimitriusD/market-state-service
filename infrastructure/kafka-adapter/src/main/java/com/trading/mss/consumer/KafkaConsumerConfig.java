package com.trading.mss.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, DepthDiffEvent> depthDiffConsumerFactory(
            ObjectMapper objectMapper,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId) {

        JsonDeserializer<DepthDiffEvent> deserializer = new JsonDeserializer<>(DepthDiffEvent.class, objectMapper);
        deserializer.setUseTypeHeaders(false);

        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DepthDiffEvent> depthDiffListenerContainerFactory(
            ConsumerFactory<String, DepthDiffEvent> depthDiffConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DepthDiffEvent>();
        factory.setConsumerFactory(depthDiffConsumerFactory);
        return factory;
    }

    @Bean
    public DepthDiffConsumer depthDiffConsumer(ProcessDepthDiffUseCase processDepthDiff) {
        return new DepthDiffConsumer(processDepthDiff);
    }
}
