package com.company.service.event;

import com.company.service.application.domain.model.Item;
import com.company.service.application.port.output.ItemEventPublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@RequiredArgsConstructor
public class KafkaItemEventPublisher implements ItemEventPublisherPort {

    private final KafkaTemplate<String, ItemEvent> kafkaTemplate;
    private final String topic;

    @Override
    public void publishCreated(Item item) {
        ItemEvent event = ItemEvent.created(item.getItemId(), item.getName(), item.getDescription(), item.isEnabled());
        send(item.getItemId(), event);
    }

    @Override
    public void publishUpdated(Item item) {
        ItemEvent event = ItemEvent.updated(item.getItemId(), item.getName(), item.getDescription(), item.isEnabled());
        send(item.getItemId(), event);
    }

    @Override
    public void publishDeleted(String itemId) {
        ItemEvent event = ItemEvent.deleted(itemId);
        send(itemId, event);
    }

    private void send(String key, ItemEvent event) {
        try {
            kafkaTemplate.send(topic, key, event);
            log.debug("Published {} for itemId={}", event.type(), key);
        } catch (Exception e) {
            log.error("Failed to publish {} for itemId={}", event.type(), key, e);
        }
    }
}
