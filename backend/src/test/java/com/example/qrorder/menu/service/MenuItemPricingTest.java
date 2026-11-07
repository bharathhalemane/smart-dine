package com.example.qrorder.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.menu.model.AddOnGroup;
import com.example.qrorder.menu.model.AddOnOption;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.model.MenuVariant;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuItemPricingTest {

    /** A pizza at 28000 paise: Half (-10000) / Full (0), and an Extras group (0 to 2 of cheese 3000, jalapeno 2000). */
    private static MenuItem pizza(boolean withVariants, int extrasMin, int extrasMax) {
        MenuItem item = new MenuItem("h", "c", "Pizza", null, 28000L, null, 0, Instant.now());
        if (withVariants) {
            item.setVariants(List.of(new MenuVariant("half", "Half", -10000), new MenuVariant("full", "Full", 0)));
        }
        item.setAddOnGroups(List.of(new AddOnGroup("extras", "Extras", extrasMin, extrasMax, List.of(
                new AddOnOption("cheese", "Extra cheese", 3000), new AddOnOption("jalapeno", "Jalapenos", 2000)))));
        return item;
    }

    @Test
    void anItemWithNoChoicesCostsItsBasePrice() {
        MenuItem plain = new MenuItem("h", "c", "Water", null, 2000L, null, 0, Instant.now());

        MenuItemPricing.PricedSelection priced = MenuItemPricing.price(plain, null, null);

        assertThat(priced.unitPriceMinor()).isEqualTo(2000L);
        assertThat(priced.options()).isEmpty();
        assertThat(priced.variantId()).isNull();
    }

    @Test
    void theVariantDeltaAndTheAddOnsAreAddedToTheBasePrice() {
        MenuItemPricing.PricedSelection priced =
                MenuItemPricing.price(pizza(true, 0, 2), "half", List.of("cheese", "jalapeno"));

        assertThat(priced.unitPriceMinor()).isEqualTo(28000 - 10000 + 3000 + 2000);
        assertThat(priced.variantName()).isEqualTo("Half");
        assertThat(priced.options()).extracting(MenuItemPricing.ChosenOption::optionName)
                .containsExactlyInAnyOrder("Extra cheese", "Jalapenos");
        assertThat(priced.options()).extracting(MenuItemPricing.ChosenOption::groupName).containsOnly("Extras");
    }

    @Test
    void aVariantIsRequiredWhenTheItemHasVariantsAndForbiddenWhenItDoesNot() {
        assertThatThrownBy(() -> MenuItemPricing.price(pizza(true, 0, 2), null, List.of()))
                .isInstanceOf(DomainException.class).hasMessageContaining("Choose a variant");
        assertThatThrownBy(() -> MenuItemPricing.price(pizza(true, 0, 2), "huge", List.of()))
                .isInstanceOf(DomainException.class).hasMessageContaining("Unknown variant");
        assertThatThrownBy(() -> MenuItemPricing.price(pizza(false, 0, 2), "half", List.of()))
                .isInstanceOf(DomainException.class).hasMessageContaining("no variants");
    }

    @Test
    void aGroupWithAMinimumMustBeSatisfied() {
        assertThatThrownBy(() -> MenuItemPricing.price(pizza(true, 1, 2), "full", List.of()))
                .isInstanceOf(DomainException.class).hasMessageContaining("at least 1 from 'Extras'");

        assertThat(MenuItemPricing.price(pizza(true, 1, 2), "full", List.of("cheese")).unitPriceMinor())
                .isEqualTo(31000L);
    }

    @Test
    void aGroupMaximumIsEnforced() {
        assertThatThrownBy(() -> MenuItemPricing.price(pizza(true, 0, 1), "full", List.of("cheese", "jalapeno")))
                .isInstanceOf(DomainException.class).hasMessageContaining("at most 1 from 'Extras'");
    }

    @Test
    void duplicateAndUnknownAddOnsAreRejected() {
        assertThatThrownBy(() -> MenuItemPricing.price(pizza(true, 0, 2), "full", List.of("cheese", "cheese")))
                .isInstanceOf(DomainException.class).hasMessageContaining("more than once");
        assertThatThrownBy(() -> MenuItemPricing.price(pizza(true, 0, 2), "full", List.of("caviar")))
                .isInstanceOf(DomainException.class).hasMessageContaining("Unknown add-on");
    }

    @Test
    void anOptionalGroupMayBeSkipped() {
        assertThat(MenuItemPricing.price(pizza(true, 0, 2), "full", List.of()).unitPriceMinor()).isEqualTo(28000L);
    }

    @Test
    void severalGroupsAreCheckedIndependently() {
        MenuItem item = pizza(true, 0, 2);
        item.setAddOnGroups(List.of(
                new AddOnGroup("sauce", "Sauce", 1, 1, List.of(new AddOnOption("red", "Red", 0), new AddOnOption("white", "White", 1000))),
                new AddOnGroup("extras", "Extras", 0, 1, List.of(new AddOnOption("cheese", "Extra cheese", 3000)))));

        assertThat(MenuItemPricing.price(item, "full", List.of("white", "cheese")).unitPriceMinor()).isEqualTo(32000L);
        assertThatThrownBy(() -> MenuItemPricing.price(item, "full", List.of("red", "white")))
                .isInstanceOf(DomainException.class).hasMessageContaining("at most 1 from 'Sauce'");
        assertThatThrownBy(() -> MenuItemPricing.price(item, "full", List.of("cheese")))
                .isInstanceOf(DomainException.class).hasMessageContaining("at least 1 from 'Sauce'");
    }
}