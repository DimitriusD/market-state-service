package com.company.service.repository;

import com.company.service.entity.ItemEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface ItemRepository extends CrudRepository<ItemEntity, Long> {

    Optional<ItemEntity> findByItemId(String itemId);

    void deleteByItemId(String itemId);
}
