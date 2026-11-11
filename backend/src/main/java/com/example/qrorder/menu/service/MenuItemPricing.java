package com.example.qrorder.menu.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.menu.model.AddOnGroup;
import com.example.qrorder.menu.model.AddOnOption;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.model.MenuVariant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public final class MenuItemPricing {

    public record ChosenOption(String groupId, String groupName, String optionId, String optionName, long priceMinor) {}

    /** Everything an order line needs to snapshot, so later menu edits cannot change old orders. */
    public record PricedSelection(long unitPriceMinor, String variantId, String variantName,
                                  List<ChosenOption> options) {}

    private MenuItemPricing() {}

    public static PricedSelection price(MenuItem item, String variantId, Collection<String> optionIds) {
        long unitPrice = item.getPriceMinor();

        // 1. variant: exactly one if the item has any, none otherwise
        String chosenVariantId = null;
        String chosenVariantName = null;
        List<MenuVariant> variants = item.getVariants();
        if (variants.isEmpty()) {
            if (variantId != null) {
                throw invalid("This item has no variants");
            }
        } else {
            if (variantId == null) {
                throw invalid("Choose a variant for " + item.getName());
            }
            MenuVariant variant = variants.stream().filter(v -> v.id().equals(variantId)).findFirst()
                    .orElseThrow(() -> invalid("Unknown variant"));
            unitPrice = Math.addExact(unitPrice, variant.priceDeltaMinor());
            chosenVariantId = variant.id();
            chosenVariantName = variant.name();
        }

        // 2. add-ons: known ids only, no duplicates, each group within its min and max
        List<String> requested = optionIds == null ? List.of() : List.copyOf(optionIds);
        if (new HashSet<>(requested).size() != requested.size()) {
            throw invalid("The same add-on was chosen more than once");
        }
        Set<String> unmatched = new HashSet<>(requested);

        List<ChosenOption> chosen = new ArrayList<>();
        for (AddOnGroup group : item.getAddOnGroups()) {
            List<AddOnOption> picked = group.options().stream().filter(o -> unmatched.contains(o.id())).toList();
            if (picked.size() < group.minSelect()) {
                throw invalid("Choose at least " + group.minSelect() + " from '" + group.name() + "'");
            }
            if (picked.size() > group.maxSelect()) {
                throw invalid("Choose at most " + group.maxSelect() + " from '" + group.name() + "'");
            }
            for (AddOnOption option : picked) {
                unmatched.remove(option.id());
                unitPrice = Math.addExact(unitPrice, option.priceMinor());
                chosen.add(new ChosenOption(group.id(), group.name(), option.id(), option.name(), option.priceMinor()));
            }
        }
        if (!unmatched.isEmpty()) {
            throw invalid("Unknown add-on");
        }
        if (unitPrice < 0) {
            throw invalid("The price cannot be negative"); // cannot happen for validated menus; belt and braces
        }
        return new PricedSelection(unitPrice, chosenVariantId, chosenVariantName, chosen);
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}