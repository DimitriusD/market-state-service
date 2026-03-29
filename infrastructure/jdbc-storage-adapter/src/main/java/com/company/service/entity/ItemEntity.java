package com.company.service.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("items")
public class ItemEntity {

    @Id
    private Long id;
    private String itemId;
    private String name;
    private String description;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
