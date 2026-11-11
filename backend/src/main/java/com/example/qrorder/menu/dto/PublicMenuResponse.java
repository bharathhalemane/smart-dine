package com.example.qrorder.menu.dto;

import com.example.qrorder.menu.model.AddOnGroup;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.model.MenuVariant;
import java.util.List;

/** What a customer sees. Deliberately has no hotel-internal fields (no active flag, no timestamps). */
public record PublicMenuResponse(String hotelId, String hotelName, String currency, List<Category> categories) {

    public record Category(String id, String name, List<Item> items) {}

    public record Item(String id, String name, String description, long priceMinor, Diet diet, boolean available,
                       List<MenuVariant> variants, List<AddOnGroup> addOnGroups, String imageUrl) {

        public static Item from(MenuItem i) {
            return new Item(i.getId(), i.getName(), i.getDescription(), i.getPriceMinor(), i.getDiet(),
                    i.isAvailable(), i.getVariants(), i.getAddOnGroups(), i.getImageUrl());
        }
    }
}