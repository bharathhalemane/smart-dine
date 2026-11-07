package com.example.qrorder.menu.service;

import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The menu as other modules may use it: only items a customer can actually see. */
@Service
public class MenuCatalogService {

    private final MenuItemRepository items;
    private final MenuCategoryRepository categories;

    public MenuCatalogService(MenuItemRepository items, MenuCategoryRepository categories) {
        this.items = items;
        this.categories = categories;
    }

    /**
     * The requested items of this hotel that are active and sit in an active category, by id.
     * Unknown ids, other hotels' ids, removed items and items in removed categories are simply absent.
     * Availability (sold out) is NOT filtered here: the caller reports that case separately.
     */
    public Map<String, MenuItem> findVisibleItems(String hotelId, Collection<String> itemIds) {
        Set<String> liveCategories = categories.findByHotelIdAndActiveTrue(hotelId).stream()
                .map(MenuCategory::getId).collect(Collectors.toSet());
        return items.findByHotelIdAndIdIn(hotelId, itemIds).stream()
                .filter(MenuItem::isActive)
                .filter(item -> liveCategories.contains(item.getCategoryId()))
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
    }
}