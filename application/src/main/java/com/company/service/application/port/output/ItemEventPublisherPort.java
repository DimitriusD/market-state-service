package com.company.service.application.port.output;

import com.company.service.application.domain.model.Item;

public interface ItemEventPublisherPort {

    void publishCreated(Item item);

    void publishUpdated(Item item);

    void publishDeleted(String itemId);
}
