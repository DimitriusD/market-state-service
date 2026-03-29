package com.company.service.rest;

import com.company.service.application.domain.model.Item;
import com.company.service.application.port.input.ItemService;
import com.company.service.rest.mapper.ItemWebMapper;
import com.company.service.restapi.generated.api.ItemsApi;
import com.company.service.restapi.generated.model.CreateItemRequestWebDto;
import com.company.service.restapi.generated.model.ItemResponseWebDto;
import com.company.service.restapi.generated.model.SetEnabledRequestWebDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ItemController implements ItemsApi {

    private final ItemService itemService;
    private final ItemWebMapper mapper;

    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponseWebDto createItem(CreateItemRequestWebDto body) {
        Item item = mapper.toDomain(body);
        Item created = itemService.create(item);
        return mapper.toWebDto(created);
    }

    @Override
    public ItemResponseWebDto getItem(String itemId) {
        return mapper.toWebDto(itemService.get(itemId));
    }

    @Override
    public List<ItemResponseWebDto> listItems() {
        return itemService.list().stream()
                .map(mapper::toWebDto)
                .toList();
    }

    @Override
    public ItemResponseWebDto setItemEnabled(String itemId, SetEnabledRequestWebDto body) {
        return mapper.toWebDto(itemService.setEnabled(itemId, body.getEnabled()));
    }

    @Override
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(String itemId) {
        itemService.delete(itemId);
    }
}
