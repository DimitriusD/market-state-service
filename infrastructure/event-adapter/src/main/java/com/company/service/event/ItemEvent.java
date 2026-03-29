package com.company.service.event;

import java.time.Instant;

public record ItemEvent(
        String type,
        String itemId,
        String name,
        String description,
        Boolean enabled,
        Instant timestamp
) {
    public static ItemEvent created(String itemId, String name, String description, boolean enabled) {
        return new ItemEvent("ITEM_CREATED", itemId, name, description, enabled, Instant.now());
    }

    public static ItemEvent updated(String itemId, String name, String description, boolean enabled) {
        return new ItemEvent("ITEM_UPDATED", itemId, name, description, enabled, Instant.now());
    }

    public static ItemEvent deleted(String itemId) {
        return new ItemEvent("ITEM_DELETED", itemId, null, null, null, Instant.now());
    }
}
