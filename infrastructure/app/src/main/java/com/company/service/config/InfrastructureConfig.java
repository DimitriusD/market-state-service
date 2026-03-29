package com.company.service.config;

import com.company.service.application.port.input.ItemService;
import com.company.service.application.port.output.ItemEventPublisherPort;
import com.company.service.application.port.output.ItemStoragePort;
import com.company.service.application.service.ItemServiceImpl;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean
    public ItemService itemService(ItemStoragePort storagePort,
                                   ItemEventPublisherPort eventPublisherPort) {
        return new ItemServiceImpl(storagePort, eventPublisherPort);
    }
}
