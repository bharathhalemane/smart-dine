package com.example.qrorder.menu.dto;

import com.example.qrorder.menu.model.MenuCategory;
import java.time.Instant;

public record CategoryResponse(String id, String name, String description, int sortOrder, boolean active,
                               Instant createdAt) {

    public static CategoryResponse from(MenuCategory c) {
        return new CategoryResponse(c.getId(), c.getName(), c.getDescription(), c.getSortOrder(), c.isActive(),
                c.getCreatedAt());
    }
}