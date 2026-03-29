package com.company.service.application.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    private Long id;
    private String itemId;
    private String name;
    private String description;
    private boolean enabled;
}
