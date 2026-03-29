package com.company.service.application.port.output;

import com.company.service.application.domain.model.Item;

import java.util.List;
import java.util.Optional;

public interface ItemStoragePort {

    Item create(Item item);

    void setEnabled(String itemId, boolean enabled);

    Optional<Item> find(String itemId);

    List<Item> list();

    void delete(String itemId);
}
