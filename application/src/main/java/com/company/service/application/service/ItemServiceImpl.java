package com.company.service.application.service;

import com.company.service.application.domain.exception.NotFoundException;
import com.company.service.application.domain.model.Item;
import com.company.service.application.port.input.ItemService;
import com.company.service.application.port.output.ItemEventPublisherPort;
import com.company.service.application.port.output.ItemStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemStoragePort storagePort;
    private final ItemEventPublisherPort eventPublisherPort;

    @Transactional
    @Override
    public Item create(Item item) {
        Item created = storagePort.create(item);
        eventPublisherPort.publishCreated(created);
        return created;
    }

    @Transactional(readOnly = true)
    @Override
    public Item get(String itemId) {
        return storagePort.find(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found: " + itemId));
    }

    @Transactional(readOnly = true)
    @Override
    public List<Item> list() {
        return storagePort.list();
    }

    @Transactional
    @Override
    public Item setEnabled(String itemId, boolean enabled) {
        storagePort.find(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found: " + itemId));
        storagePort.setEnabled(itemId, enabled);
        Item updated = storagePort.find(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found: " + itemId));
        eventPublisherPort.publishUpdated(updated);
        return updated;
    }

    @Transactional
    @Override
    public void delete(String itemId) {
        storagePort.find(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found: " + itemId));
        storagePort.delete(itemId);
        eventPublisherPort.publishDeleted(itemId);
    }
}
