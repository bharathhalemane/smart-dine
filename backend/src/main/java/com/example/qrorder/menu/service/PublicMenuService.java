package com.example.qrorder.menu.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.menu.dto.PublicMenuResponse;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.tenancy.dto.PublicHotelInfo;
import com.example.qrorder.tenancy.service.HotelLookupService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class PublicMenuService {

    private static final String DEFAULT_CURRENCY = "INR";

    private final HotelLookupService hotels;
    private final MenuCategoryRepository categories;
    private final MenuItemRepository items;

    public PublicMenuService(HotelLookupService hotels, MenuCategoryRepository categories, MenuItemRepository items) {
        this.hotels = hotels;
        this.categories = categories;
        this.items = items;
    }

    public PublicMenuResponse menuFor(String hotelId) {
        PublicHotelInfo hotel = hotels.findActive(hotelId).orElseThrow(() -> DomainException.notFound("Menu"));
        String currency = hotels.findCurrency(hotelId).orElse(DEFAULT_CURRENCY);

        Map<String, List<MenuItem>> itemsByCategory = items.findByHotelIdAndActiveTrue(hotelId).stream()
                .collect(Collectors.groupingBy(MenuItem::getCategoryId));

        List<PublicMenuResponse.Category> result = categories.findByHotelIdAndActiveTrue(hotelId).stream()
                .sorted(Comparator.comparingInt(MenuCategory::getSortOrder).thenComparing(MenuCategory::getName))
                .map(category -> new PublicMenuResponse.Category(category.getId(), category.getName(),
                        itemsByCategory.getOrDefault(category.getId(), List.of()).stream()
                                .sorted(Comparator.comparingInt(MenuItem::getSortOrder).thenComparing(MenuItem::getName))
                                .map(PublicMenuResponse.Item::from)
                                .toList()))
                .filter(category -> !category.items().isEmpty())
                .toList();

        return new PublicMenuResponse(hotel.id(), hotel.name(), currency, result);
    }
}