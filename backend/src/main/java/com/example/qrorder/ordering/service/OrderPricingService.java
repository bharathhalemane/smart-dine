package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.service.MenuCatalogService;
import com.example.qrorder.menu.service.MenuItemPricing;
import com.example.qrorder.ordering.dto.CartLine;
import com.example.qrorder.ordering.dto.PricedCart;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderOption;
import com.example.qrorder.tenancy.service.HotelLookupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Turns a cart into priced, snapshotted order lines. The only inputs taken from the client are item ids,
 * choices, quantities and notes; every price, name and rule comes from the live menu. Step 7.4 saves
 * the result as an order using this same method.
 */
@Service
public class OrderPricingService {

    static final int MAX_TOTAL_QUANTITY = 100;
    private static final String DEFAULT_CURRENCY = "INR";

    private final MenuCatalogService catalog;
    private final HotelLookupService hotels;

    public OrderPricingService(MenuCatalogService catalog, HotelLookupService hotels) {
        this.catalog = catalog;
        this.hotels = hotels;
    }

    public PricedCart price(CustomerContext customer, List<CartLine> cart) {
        int totalQuantity = cart.stream().mapToInt(CartLine::quantity).sum();
        if (totalQuantity > MAX_TOTAL_QUANTITY) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "An order can have at most " + MAX_TOTAL_QUANTITY + " items in total");
        }

        Set<String> itemIds = cart.stream().map(CartLine::itemId).collect(Collectors.toSet());
        Map<String, MenuItem> menu = catalog.findVisibleItems(customer.hotelId(), itemIds);

        List<OrderLine> lines = new ArrayList<>();
        long subtotal = 0;
        for (CartLine line : cart) {
            MenuItem item = menu.get(line.itemId());
            if (item == null) {
                throw DomainException.conflict("An item in your cart is no longer on the menu");
            }
            if (!item.isAvailable()) {
                throw DomainException.conflict("'" + item.getName() + "' is sold out right now");
            }

            MenuItemPricing.PricedSelection priced;
            try {
                priced = MenuItemPricing.price(item, line.variantId(), line.optionIds());
            } catch (DomainException e) {
                throw new DomainException(e.code(), item.getName() + ": " + e.getMessage());
            }

            long lineTotal = Math.multiplyExact(priced.unitPriceMinor(), (long) line.quantity());
            subtotal = Math.addExact(subtotal, lineTotal);
            List<OrderOption> options = priced.options().stream()
                    .map(o -> new OrderOption(o.groupId(), o.groupName(), o.optionId(), o.optionName(), o.priceMinor()))
                    .toList();
            lines.add(new OrderLine(item.getId(), item.getName(), item.getDiet(), priced.variantId(),
                    priced.variantName(), options, priced.unitPriceMinor(), line.quantity(), lineTotal,
                    OrderNotes.clean(line.note())));
        }

        String currency = hotels.findCurrency(customer.hotelId()).orElse(DEFAULT_CURRENCY);
        return new PricedCart(currency, List.copyOf(lines), subtotal, totalQuantity);
    }
}