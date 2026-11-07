package com.example.qrorder.menu.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.menu.dto.AddOnGroupInput;
import com.example.qrorder.menu.dto.AddOnOptionInput;
import com.example.qrorder.menu.dto.ItemResponse;
import com.example.qrorder.menu.dto.ReplaceAddOnGroupsRequest;
import com.example.qrorder.menu.dto.ReplaceVariantsRequest;
import com.example.qrorder.menu.dto.VariantInput;
import com.example.qrorder.menu.model.AddOnGroup;
import com.example.qrorder.menu.model.AddOnOption;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.model.MenuVariant;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.HotelLookupService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MenuItemOptionsService {

    private static final long MAX_PRICE = 10_000_000L;
    private static final String DEFAULT_CURRENCY = "INR";

    private final MenuItemRepository items;
    private final HotelLookupService hotels;
    private final Clock clock;

    public MenuItemOptionsService(MenuItemRepository items, HotelLookupService hotels, Clock clock) {
        this.items = items;
        this.hotels = hotels;
        this.clock = clock;
    }

    /** Replaces the whole variant list. Validation runs first, so a bad entry changes nothing. */
    public ItemResponse replaceVariants(TenantContext tenant, String itemId, ReplaceVariantsRequest request) {
        tenant.require(Permission.MANAGE_MENU);
        MenuItem item = find(tenant, itemId);
        List<VariantInput> input = request.variants();
        if (input.size() == 1) {
            throw invalid("An item needs either no variants or at least two");
        }

        Set<String> existingIds = item.getVariants().stream().map(MenuVariant::id).collect(Collectors.toSet());
        Set<String> seenIds = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        List<MenuVariant> result = new ArrayList<>();

        for (VariantInput variant : input) {
            String name = MenuNames.normalize(variant.name());
            if (name.isEmpty() || !seenNames.add(MenuNames.key(name))) {
                throw invalid("Variant names must not be blank or repeated: '" + variant.name() + "'");
            }
            long finalPrice = item.getPriceMinor() + variant.priceDeltaMinor();
            if (finalPrice < 0 || finalPrice > MAX_PRICE) {
                throw invalid("The price of variant '" + name + "' must stay between 0 and " + MAX_PRICE);
            }
            result.add(new MenuVariant(resolveId(variant.id(), existingIds, seenIds, "variant"), name,
                    variant.priceDeltaMinor()));
        }

        item.setVariants(result);
        return save(tenant, item);
    }

    /** Replaces the whole add-on configuration, all or nothing. */
    public ItemResponse replaceAddOnGroups(TenantContext tenant, String itemId, ReplaceAddOnGroupsRequest request) {
        tenant.require(Permission.MANAGE_MENU);
        MenuItem item = find(tenant, itemId);

        Map<String, AddOnGroup> existingGroups = item.getAddOnGroups().stream()
                .collect(Collectors.toMap(AddOnGroup::id, Function.identity()));
        Set<String> seenGroupIds = new HashSet<>();
        Set<String> seenGroupNames = new HashSet<>();
        List<AddOnGroup> result = new ArrayList<>();

        for (AddOnGroupInput group : request.groups()) {
            String groupName = MenuNames.normalize(group.name());
            if (groupName.isEmpty() || !seenGroupNames.add(MenuNames.key(groupName))) {
                throw invalid("Group names must not be blank or repeated: '" + group.name() + "'");
            }
            if (group.minSelect() > group.maxSelect() || group.maxSelect() > group.options().size()) {
                throw invalid("Group '" + groupName + "': maxSelect must be at least minSelect "
                        + "and cannot exceed the number of options");
            }

            AddOnGroup previous = null;
            String groupId;
            if (group.id() == null || group.id().isBlank()) {
                groupId = newId();
            } else {
                previous = existingGroups.get(group.id());
                if (previous == null || !seenGroupIds.add(group.id())) {
                    throw invalid("Unknown or repeated group id");
                }
                groupId = group.id();
            }

            // Option ids are only valid inside the group they already belong to.
            Set<String> existingOptionIds = previous == null ? Set.of()
                    : previous.options().stream().map(AddOnOption::id).collect(Collectors.toSet());
            Set<String> seenOptionIds = new HashSet<>();
            Set<String> seenOptionNames = new HashSet<>();
            List<AddOnOption> options = new ArrayList<>();
            for (AddOnOptionInput option : group.options()) {
                String optionName = MenuNames.normalize(option.name());
                if (optionName.isEmpty() || !seenOptionNames.add(MenuNames.key(optionName))) {
                    throw invalid("Option names in '" + groupName + "' must not be blank or repeated: '"
                            + option.name() + "'");
                }
                options.add(new AddOnOption(resolveId(option.id(), existingOptionIds, seenOptionIds, "option"),
                        optionName, option.priceMinor()));
            }
            result.add(new AddOnGroup(groupId, groupName, group.minSelect(), group.maxSelect(), options));
        }

        item.setAddOnGroups(result);
        return save(tenant, item);
    }

    /** A given id must already exist (and be used once); no id means "create a new one". */
    private static String resolveId(String given, Set<String> existingIds, Set<String> seenIds, String what) {
        if (given == null || given.isBlank()) {
            return newId();
        }
        if (!existingIds.contains(given) || !seenIds.add(given)) {
            throw invalid("Unknown or repeated " + what + " id");
        }
        return given;
    }

    private ItemResponse save(TenantContext tenant, MenuItem item) {
        item.setUpdatedAt(clock.instant());
        MenuItem saved = items.save(item);
        return ItemResponse.from(saved, hotels.findCurrency(tenant.hotelId()).orElse(DEFAULT_CURRENCY));
    }

    private MenuItem find(TenantContext tenant, String itemId) {
        return items.findByIdAndHotelId(itemId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Item"));
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}