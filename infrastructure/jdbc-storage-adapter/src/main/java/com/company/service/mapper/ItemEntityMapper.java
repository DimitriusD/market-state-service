package com.company.service.mapper;

import com.company.service.application.domain.model.Item;
import com.company.service.entity.ItemEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ItemEntityMapper {

    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ItemEntity toEntity(Item domain);

    Item toDomain(ItemEntity entity);
}
