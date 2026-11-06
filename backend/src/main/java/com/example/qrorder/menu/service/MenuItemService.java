package com.example.qrorder.menu.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.menu.dto.CreateItemRequest;
import com.example.qrorder.menu.dto.ItemResponse;
import com.example.qrorder.menu.dto.UpdateItemRequest;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.HotelLookupService;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class MenuItemService {

    private static final String DEFAULT_CURRENCY = "INR";

    private static final Comparator<MenuItem> DISPLAY_ORDER = Comparator
            .comparingInt(MenuItem::getSortOrder)
            .thenComparing(MenuItem::getName, String.CASE_INSENSITIVE_ORDER);

    private final MenuItemRepository items;
    private final MenuCategoryRepository categories;
    private final HotelLookupService hotels;
    private final Clock clock;

    public MenuItemService(MenuItemRepository items, MenuCategoryRepository categories,
                           HotelLookupService hotels, Clock clock) {
        this.items = items;
        this.categories = categories;
        this.hotels = hotels;
        this.clock = clock;
    }

    public ItemResponse create(TenantContext tenant, CreateItemRequest request) {
        tenant.require(Permission.MANAGE_MENU);
        MenuCategory category = usableCategory(tenant, request.categoryId());
        String name = MenuNames.normalize(request.name());
        if (items.existsByHotelIdAndCategoryIdAndNameKey(tenant.hotelId(), category.getId(), MenuNames.key(name))) {
            throw nameTaken();
        }
        int sortOrder = request.sortOrder() != null ? request.sortOrder()
                : nextSortOrder(tenant.hotelId(), category.getId());
        try {
            MenuItem saved = items.save(new MenuItem(tenant.hotelId(), category.getId(), name,
                    trimToNull(request.description()), request.priceMinor(), request.diet(), sortOrder,
                    clock.instant()));
            return ItemResponse.from(saved, currency(tenant));
        } catch (DuplicateKeyException e) {
            throw nameTaken(); // two creates raced; the unique index decided
        }
    }

    public List<ItemResponse> list(TenantContext tenant, String categoryId, boolean includeInactive) {
        if (includeInactive) {
            tenant.require(Permission.MANAGE_MENU);
        }
        List<MenuItem> found = categoryId == null
                ? items.findByHotelId(tenant.hotelId())
                : items.findByHotelIdAndCategoryId(tenant.hotelId(), categoryId);
        String currency = currency(tenant);
        return found.stream()
                .filter(item -> includeInactive || item.isActive())
                .sorted(DISPLAY_ORDER)
                .map(item -> ItemResponse.from(item, currency))
                .toList();
    }

    public ItemResponse get(TenantContext tenant, String itemId) {
        return ItemResponse.from(find(tenant, itemId), currency(tenant));
    }

    public ItemResponse update(TenantContext tenant, String itemId, UpdateItemRequest request) {
        tenant.require(Permission.MANAGE_MENU);
        MenuItem item = find(tenant, itemId);

        boolean categoryChanged = request.categoryId() != null && !request.categoryId().equals(item.getCategoryId());
        String targetCategoryId = item.getCategoryId();
        if (categoryChanged) {
            targetCategoryId = usableCategory(tenant, request.categoryId()).getId();
        }

        String name = item.getName();
        if (request.name() != null) {
            name = MenuNames.normalize(request.name());
            if (name.isEmpty()) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, "Item name must not be blank");
            }
        }
        boolean keyChanged = !MenuNames.key(name).equals(item.getNameKey());
        if ((categoryChanged || keyChanged)
                && items.existsByHotelIdAndCategoryIdAndNameKey(tenant.hotelId(), targetCategoryId, MenuNames.key(name))) {
            throw nameTaken();
        }

        if (categoryChanged) {
            item.setCategoryId(targetCategoryId);
            if (request.sortOrder() == null) {
                item.setSortOrder(nextSortOrder(tenant.hotelId(), targetCategoryId)); // goes to the end of its new category
            }
        }
        item.setName(name); // a change of case only is allowed
        if (request.description() != null) {
            item.setDescription(trimToNull(request.description()));
        }
        if (request.priceMinor() != null) {
            item.setPriceMinor(request.priceMinor());
        }
        if (request.diet() != null) {
            item.setDiet(request.diet());
        }
        if (request.sortOrder() != null) {
            item.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            item.setActive(request.active());
        }
        item.setUpdatedAt(clock.instant());

        try {
            return ItemResponse.from(items.save(item), currency(tenant));
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
    }

    /** Soft delete: old orders keep pointing at this item. */
    public void deactivate(TenantContext tenant, String itemId) {
        tenant.require(Permission.MANAGE_MENU);
        MenuItem item = find(tenant, itemId);
        item.setActive(false);
        item.setUpdatedAt(clock.instant());
        items.save(item);
    }

    /** Same hotel and active. A foreign or unknown category looks exactly like a missing one. */
    private MenuCategory usableCategory(TenantContext tenant, String categoryId) {
        MenuCategory category = categories.findByIdAndHotelId(categoryId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Category"));
        if (!category.isActive()) {
            throw DomainException.conflict("This category is deactivated");
        }
        return category;
    }


    private MenuItem find(TenantContext tenant, String itemId) {
        return items.findByIdAndHotelId(itemId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Item"));
    }

    private int nextSortOrder(String hotelId, String categoryId) {
        return items.findByHotelIdAndCategoryId(hotelId, categoryId).stream()
                .mapToInt(MenuItem::getSortOrder).max().orElse(-1) + 1;
    }

    private String currency(TenantContext tenant) {
        return hotels.findCurrency(tenant.hotelId()).orElse(DEFAULT_CURRENCY);
    }

    private static DomainException nameTaken() {
        return DomainException.conflict("An item with this name already exists in this category");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}