package com.example.qrorder.menu.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.menu.dto.ItemResponse;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.HotelLookupService;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class MenuAvailabilityService {

    private static final String DEFAULT_CURRENCY = "INR";

    private final MenuItemRepository items;
    private final HotelLookupService hotels;
    private final Clock clock;

    public MenuAvailabilityService(MenuItemRepository items, HotelLookupService hotels, Clock clock) {
        this.items = items;
        this.hotels = hotels;
        this.clock = clock;
    }

    /** Sets (not flips) availability, so repeating the call is harmless. */
    public ItemResponse setAvailable(TenantContext tenant, String itemId, boolean available) {
        tenant.require(Permission.TOGGLE_AVAILABILITY);
        MenuItem item = items.findByIdAndHotelId(itemId, tenant.hotelId())
                .filter(MenuItem::isActive)
                .orElseThrow(() -> DomainException.notFound("Item"));
        if (item.isAvailable() != available) {
            item.setAvailable(available);
            item.setUpdatedAt(clock.instant());
            item = items.save(item);
        }
        return ItemResponse.from(item, hotels.findCurrency(tenant.hotelId()).orElse(DEFAULT_CURRENCY));
    }
}