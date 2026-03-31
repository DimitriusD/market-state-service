package com.trading.mss.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookTopNStatePort;
import com.trading.mss.publisher.KafkaBboStatePublisher;
import com.trading.mss.publisher.KafkaOrderBookTopNStatePublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> kafkaProducerFactory(
            ObjectMapper objectMapper,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {

        JsonSerializer<Object> serializer = new JsonSerializer<>(objectMapper);

        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers
        );

        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), serializer);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }

    @Bean
    public PublishBboStatePort publishBboStatePort(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.bbo-state:state.bbo.v1}") String topic) {
        return new KafkaBboStatePublisher(kafkaTemplate, topic);
    }

    @Bean
    public PublishOrderBookTopNStatePort publishOrderBookTopNStatePort(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.orderbook-topn-state:state.orderbook.l2.topn.v1}") String topic) {
        return new KafkaOrderBookTopNStatePublisher(kafkaTemplate, topic);
    }
}
