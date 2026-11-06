package com.example.qrorder.menu.dto;

import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuItem;
import java.time.Instant;

public record ItemResponse(String id, String categoryId, String name, String description, long priceMinor,
                           String currency, Diet diet, int sortOrder, boolean active, boolean available,
                           Instant createdAt) {

    public static ItemResponse from(MenuItem i, String currency) {
        return new ItemResponse(i.getId(), i.getCategoryId(), i.getName(), i.getDescription(), i.getPriceMinor(),
                currency, i.getDiet(), i.getSortOrder(), i.isActive(), i.isAvailable(), i.getCreatedAt());
    }
}