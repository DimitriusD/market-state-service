package com.company.service.application.port.input;

import com.company.service.application.domain.model.Item;

import java.util.List;

public interface ItemService {

    Item create(Item item);

    Item get(String itemId);

    List<Item> list();

    Item setEnabled(String itemId, boolean enabled);

    void delete(String itemId);
}
