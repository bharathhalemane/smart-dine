package com.example.qrorder.menu.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.menu.dto.CategoryResponse;
import com.example.qrorder.menu.dto.CreateCategoryRequest;
import com.example.qrorder.menu.dto.ReorderCategoriesRequest;
import com.example.qrorder.menu.dto.UpdateCategoryRequest;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuCategoryService {

    private static final Comparator<MenuCategory> DISPLAY_ORDER = Comparator
            .comparingInt(MenuCategory::getSortOrder)
            .thenComparing(MenuCategory::getName, String.CASE_INSENSITIVE_ORDER);

    private final MenuCategoryRepository categories;
    private final Clock clock;

    public MenuCategoryService(MenuCategoryRepository categories, Clock clock) {
        this.categories = categories;
        this.clock = clock;
    }

    public CategoryResponse create(TenantContext tenant, CreateCategoryRequest request) {
        tenant.require(Permission.MANAGE_MENU);
        String name = MenuNames.normalize(request.name());
        if (categories.existsByHotelIdAndNameKey(tenant.hotelId(), MenuNames.key(name))) {
            throw nameTaken();
        }
        int sortOrder = request.sortOrder() != null ? request.sortOrder() : nextSortOrder(tenant.hotelId());
        try {
            return CategoryResponse.from(categories.save(new MenuCategory(tenant.hotelId(), name,
                    trimToNull(request.description()), sortOrder, clock.instant())));
        } catch (DuplicateKeyException e) {
            throw nameTaken(); // two creates raced; the unique index decided
        }
    }

    public List<CategoryResponse> list(TenantContext tenant, boolean includeInactive) {
        if (includeInactive) {
            tenant.require(Permission.MANAGE_MENU);
        }
        List<MenuCategory> found = includeInactive
                ? categories.findByHotelId(tenant.hotelId())
                : categories.findByHotelIdAndActiveTrue(tenant.hotelId());
        return found.stream().sorted(DISPLAY_ORDER).map(CategoryResponse::from).toList();
    }

    public CategoryResponse get(TenantContext tenant, String categoryId) {
        return CategoryResponse.from(find(tenant, categoryId));
    }

    public CategoryResponse update(TenantContext tenant, String categoryId, UpdateCategoryRequest request) {
        tenant.require(Permission.MANAGE_MENU);
        MenuCategory category = find(tenant, categoryId);

        if (request.name() != null) {
            String name = MenuNames.normalize(request.name());
            if (name.isEmpty()) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, "Category name must not be blank");
            }
            if (!MenuNames.key(name).equals(category.getNameKey())
                    && categories.existsByHotelIdAndNameKey(tenant.hotelId(), MenuNames.key(name))) {
                throw nameTaken();
            }
            category.setName(name); // a change of case only ("starters" to "Starters") is allowed
        }
        if (request.description() != null) {
            category.setDescription(trimToNull(request.description()));
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        category.setUpdatedAt(clock.instant());

        try {
            return CategoryResponse.from(categories.save(category));
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
    }

    /** Soft delete: items (and old orders) keep pointing at this category. */
    public void deactivate(TenantContext tenant, String categoryId) {
        tenant.require(Permission.MANAGE_MENU);
        MenuCategory category = find(tenant, categoryId);
        category.setActive(false);
        category.setUpdatedAt(clock.instant());
        categories.save(category);
    }

    /** All or nothing: the request must list every category of this hotel exactly once. */
    @Transactional
    public List<CategoryResponse> reorder(TenantContext tenant, ReorderCategoriesRequest request) {
        tenant.require(Permission.MANAGE_MENU);
        List<String> requested = request.categoryIds();
        Set<String> requestedSet = new HashSet<>(requested);

        Map<String, MenuCategory> existing = categories.findByHotelId(tenant.hotelId()).stream()
                .collect(Collectors.toMap(MenuCategory::getId, Function.identity()));

        if (requestedSet.size() != requested.size() || !requestedSet.equals(existing.keySet())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "List every category of the hotel exactly once");
        }

        Instant now = clock.instant();
        for (int position = 0; position < requested.size(); position++) {
            MenuCategory category = existing.get(requested.get(position));
            category.setSortOrder(position);
            category.setUpdatedAt(now);
        }
        categories.saveAll(existing.values());

        return existing.values().stream().sorted(DISPLAY_ORDER).map(CategoryResponse::from).toList();
    }

    private int nextSortOrder(String hotelId) {
        return categories.findByHotelId(hotelId).stream().mapToInt(MenuCategory::getSortOrder).max().orElse(-1) + 1;
    }

    /** The only way services load a category by id: scoped to the caller's hotel. */
    private MenuCategory find(TenantContext tenant, String categoryId) {
        return categories.findByIdAndHotelId(categoryId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Category"));
    }

    private static DomainException nameTaken() {
        return DomainException.conflict("A category with this name already exists");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}