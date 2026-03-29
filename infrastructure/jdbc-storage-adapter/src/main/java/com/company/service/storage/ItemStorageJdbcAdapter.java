package com.company.service.storage;

import com.company.service.application.domain.exception.NotFoundException;
import com.company.service.application.domain.model.Item;
import com.company.service.application.port.output.ItemStoragePort;
import com.company.service.entity.ItemEntity;
import com.company.service.mapper.ItemEntityMapper;
import com.company.service.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Repository
@RequiredArgsConstructor
public class ItemStorageJdbcAdapter implements ItemStoragePort {

    private final ItemRepository repository;
    private final ItemEntityMapper mapper;

    @Transactional
    @Override
    public Item create(Item item) {
        if (item.getItemId() == null) {
            item.setItemId(UUID.randomUUID().toString());
        }
        ItemEntity entity = mapper.toEntity(item);
        ItemEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Transactional
    @Override
    public void setEnabled(String itemId, boolean enabled) {
        ItemEntity entity = repository.findByItemId(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found: " + itemId));
        entity.setEnabled(enabled);
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<Item> find(String itemId) {
        return repository.findByItemId(itemId).map(mapper::toDomain);
    }

    @Transactional(readOnly = true)
    @Override
    public List<Item> list() {
        return StreamSupport.stream(repository.findAll().spliterator(), false)
                .map(mapper::toDomain)
                .toList();
    }

    @Transactional
    @Override
    public void delete(String itemId) {
        repository.findByItemId(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found: " + itemId));
        repository.deleteByItemId(itemId);
    }
}
