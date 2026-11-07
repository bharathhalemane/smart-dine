package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.menu.model.AddOnGroup;
import com.example.qrorder.menu.model.AddOnOption;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.model.MenuVariant;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderOption;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.repository.HotelRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CartPricingIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired HotelRepository hotels;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;
    @Autowired MenuCategoryRepository categories;
    @Autowired MenuItemRepository items;
    @Autowired OrderRepository orders;

    String hotelId;
    String starters;
    String soup;   // 15000
    String pizza;  // 28000, Half -10000 / Full 0, Extras 0..2 (cheese 3000, jalapeno 2000)
    String cookie;

    @BeforeEach
    void seed() throws Exception {
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        categories.deleteAll();
        items.deleteAll();
        orders.deleteAll();
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));

        starters = categories.save(new MenuCategory(hotelId, "Starters", null, 1, now)).getId();
        soup = items.save(new MenuItem(hotelId, starters, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        MenuItem pizzaItem = new MenuItem(hotelId, starters, "Pizza", null, 28000L, Diet.VEG, 1, now);
        pizzaItem.setVariants(List.of(new MenuVariant("half", "Half", -10000), new MenuVariant("full", "Full", 0)));
        pizzaItem.setAddOnGroups(List.of(new AddOnGroup("extras", "Extras", 0, 2, List.of(
                new AddOnOption("cheese", "Extra cheese", 3000), new AddOnOption("jalapeno", "Jalapenos", 2000)))));
        pizza = items.save(pizzaItem).getId();

        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-t1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        cookie = matcher.group(1);
    }

    private ResultActions price(String cookieValue, String json) throws Exception {
        var request = post("/api/v1/customer/cart/price").contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookieValue != null) {
            request.cookie(new Cookie("qr_customer", cookieValue));
        }
        return mvc.perform(request);
    }

    private static String cart(String... lines) {
        return "{\"lines\":[" + String.join(",", lines) + "]}";
    }

    private static String line(String itemId, String variantId, String optionIdsJson, int quantity) {
        return "{\"itemId\":\"%s\",\"variantId\":%s,\"optionIds\":%s,\"quantity\":%d}".formatted(itemId,
                variantId == null ? "null" : "\"" + variantId + "\"", optionIdsJson, quantity);
    }

    @Test
    void aCartIsPricedFromTheMenuWithFullSnapshotsAndNothingIsSaved() throws Exception {
        price(cookie, cart(
                line(soup, null, "[]", 2),
                line(pizza, "half", "[\"cheese\",\"jalapeno\"]", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.totalQuantity").value(3))
                .andExpect(jsonPath("$.subtotalMinor").value(53000))
                .andExpect(jsonPath("$.lines[0].itemName").value("Soup"))
                .andExpect(jsonPath("$.lines[0].unitPriceMinor").value(15000))
                .andExpect(jsonPath("$.lines[0].lineTotalMinor").value(30000))
                .andExpect(jsonPath("$.lines[1].itemName").value("Pizza"))
                .andExpect(jsonPath("$.lines[1].variantName").value("Half"))
                .andExpect(jsonPath("$.lines[1].unitPriceMinor").value(23000))
                .andExpect(jsonPath("$.lines[1].options.length()").value(2))
                .andExpect(jsonPath("$.lines[1].options[0].groupName").value("Extras"));

        assertThat(orders.count()).isZero();
    }

    @Test
    void theClientCannotInfluenceThePrice() throws Exception {
        price(cookie, "{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1,\"priceMinor\":1,\"unitPriceMinor\":1,\"lineTotalMinor\":1}],\"subtotalMinor\":1}"
                .formatted(soup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPriceMinor").value(15000))
                .andExpect(jsonPath("$.subtotalMinor").value(15000));
    }

    @Test
    void theQuoteFollowsLiveMenuChanges() throws Exception {
        price(cookie, cart(line(soup, null, "[]", 1))).andExpect(jsonPath("$.subtotalMinor").value(15000));

        MenuItem item = items.findById(soup).orElseThrow();
        item.setPriceMinor(18000L);
        items.save(item);

        price(cookie, cart(line(soup, null, "[]", 1))).andExpect(jsonPath("$.subtotalMinor").value(18000));
    }

    @Test
    void invalidChoicesAndSizesAreRejected() throws Exception {
        List<String> bad = List.of(
                cart(line(pizza, null, "[]", 1)),                                       // variant required
                cart(line(pizza, "huge", "[]", 1)),                                     // unknown variant
                cart(line(soup, "half", "[]", 1)),                                      // item has no variants
                cart(line(pizza, "full", "[\"cheese\",\"jalapeno\",\"cheese\"]", 1)),   // duplicate add-on
                cart(line(pizza, "full", "[\"caviar\"]", 1)),                           // unknown add-on
                cart(line(soup, null, "[]", 0)),                                        // quantity too small
                cart(line(soup, null, "[]", 21)),                                       // quantity too large
                "{\"lines\":[]}",                                                       // empty cart
                "{}",                                                                   // no lines at all
                cart(line(soup, null, "[]", 20), line(soup, null, "[]", 20), line(soup, null, "[]", 20),
                        line(soup, null, "[]", 20), line(soup, null, "[]", 20), line(soup, null, "[]", 20)), // 120 items
                "{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1,\"note\":\"hot\\u0007\"}]}".formatted(soup)); // control char
        for (String json : bad) {
            price(cookie, json).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void itemsThatCannotBeOrderedAreRefusedWithAConflict() throws Exception {
        MenuItem soldOut = new MenuItem(hotelId, starters, "Sold out dish", null, 9000L, Diet.VEG, 2, Instant.now());
        soldOut.setAvailable(false);
        String soldOutId = items.save(soldOut).getId();

        MenuItem removed = new MenuItem(hotelId, starters, "Removed dish", null, 9000L, Diet.VEG, 3, Instant.now());
        removed.setActive(false);
        String removedId = items.save(removed).getId();

        MenuCategory retired = new MenuCategory(hotelId, "Retired", null, 9, Instant.now());
        retired.setActive(false);
        String retiredCat = categories.save(retired).getId();
        String inRetired = items.save(new MenuItem(hotelId, retiredCat, "Orphan", null, 9000L, Diet.VEG, 0,
                Instant.now())).getId();

        String otherHotel = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        String otherCat = categories.save(new MenuCategory(otherHotel, "Drinks", null, 1, Instant.now())).getId();
        String foreign = items.save(new MenuItem(otherHotel, otherCat, "Lassi", null, 6000L, Diet.VEG, 0,
                Instant.now())).getId();

        price(cookie, cart(line(soldOutId, null, "[]", 1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("'Sold out dish' is sold out right now"));
        for (String id : List.of(removedId, inRetired, foreign, "does-not-exist")) {
            price(cookie, cart(line(id, null, "[]", 1)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        }
    }

    @Test
    void aValidCustomerCookieIsRequiredAndItDiesWithTheTableVisit() throws Exception {
        price(null, cart(line(soup, null, "[]", 1)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        price("garbage", cart(line(soup, null, "[]", 1)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        DiningSession visit = sessions.findAll().get(0);
        visit.close(Instant.now());
        sessions.save(visit);

        price(cookie, cart(line(soup, null, "[]", 1)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
    }

    @Test
    void anOrderDocumentRoundTripsWithItsEmbeddedSnapshots() {
        OrderLine pizzaLine = new OrderLine(pizza, "Pizza", Diet.VEG, "half", "Half",
                List.of(new OrderOption("extras", "Extras", "cheese", "Extra cheese", 3000)),
                21000L, 2, 42000L, "less cheese");
        Order saved = orders.save(new Order(hotelId, "ds-1", "t-1", "T1", "cust-1", "Guest 1", "INR",
                List.of(pizzaLine), 42000L, null, null, Instant.now()));

        Order read = orders.findById(saved.getId()).orElseThrow();

        assertThat(read.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(read.getCustomerLabel()).isEqualTo("Guest 1");
        assertThat(read.getSubtotalMinor()).isEqualTo(42000L);
        assertThat(read.getLines()).hasSize(1);
        OrderLine line = read.getLines().get(0);
        assertThat(line.itemName()).isEqualTo("Pizza");
        assertThat(line.variantName()).isEqualTo("Half");
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.lineTotalMinor()).isEqualTo(42000L);
        assertThat(line.options()).extracting(OrderOption::optionName).containsExactly("Extra cheese");
    }
}