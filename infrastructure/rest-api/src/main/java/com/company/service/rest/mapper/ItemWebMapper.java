package com.company.service.rest.mapper;

import com.company.service.application.domain.model.Item;
import com.company.service.restapi.generated.model.CreateItemRequestWebDto;
import com.company.service.restapi.generated.model.ItemResponseWebDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ItemWebMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "itemId", ignore = true)
    @Mapping(target = "enabled", source = "enabled", defaultValue = "false")
    Item toDomain(CreateItemRequestWebDto dto);

    ItemResponseWebDto toWebDto(Item item);
}
