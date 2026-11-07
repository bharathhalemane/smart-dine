package com.example.qrorder.menu.dto;

import com.example.qrorder.menu.model.AddOnGroup;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.model.MenuVariant;
import java.time.Instant;
import java.util.List;

public record ItemResponse(String id, String categoryId, String name, String description, long priceMinor,
                           String currency, Diet diet, int sortOrder, boolean active, boolean available,
                           Instant createdAt, List<MenuVariant> variants, List<AddOnGroup> addOnGroups) {

    public static ItemResponse from(MenuItem i, String currency) {
        return new ItemResponse(i.getId(), i.getCategoryId(), i.getName(), i.getDescription(), i.getPriceMinor(),
                currency, i.getDiet(), i.getSortOrder(), i.isActive(), i.isAvailable(), i.getCreatedAt(),
                i.getVariants(), i.getAddOnGroups());
    }
}