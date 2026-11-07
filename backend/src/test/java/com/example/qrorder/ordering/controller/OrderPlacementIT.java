package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class OrderPlacementIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
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

        cookie = newDiner();
    }

    /** Scans as a brand-new diner and returns that diner's cookie token. */
    private String newDiner() throws Exception {
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-t1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private ResultActions order(String cookieValue, String key, String json) throws Exception {
        var request = post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookieValue != null) {
            request.cookie(new Cookie("qr_customer", cookieValue));
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static String cart(String... lines) {
        return "{\"lines\":[" + String.join(",", lines) + "]}";
    }

    private static String line(String itemId, String variantId, String optionIdsJson, int quantity, String note) {
        return "{\"itemId\":\"%s\",\"variantId\":%s,\"optionIds\":%s,\"quantity\":%d,\"note\":%s}".formatted(itemId,
                variantId == null ? "null" : "\"" + variantId + "\"", optionIdsJson, quantity,
                note == null ? "null" : "\"" + note + "\"");
    }

    private String soupCart(int quantity) {
        return cart(line(soup, null, "[]", quantity, null));
    }

    @Test
    void aConfirmedOrderIsSavedWithFullSnapshots() throws Exception {
        order(cookie, newKey(), cart(
                line(soup, null, "[]", 2, null),
                line(pizza, "half", "[\"cheese\"]", 1, "extra crispy")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.tableNumber").value("T1"))
                .andExpect(jsonPath("$.customerLabel").value("Guest 1"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.subtotalMinor").value(51000))
                .andExpect(jsonPath("$.lines[1].note").value("extra crispy"))
                .andExpect(jsonPath("$.hotelId").doesNotExist());

        assertThat(orders.count()).isEqualTo(1);
        Order saved = orders.findAll().get(0);
        DiningSession visit = sessions.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getHotelId()).isEqualTo(hotelId);
        assertThat(saved.getDiningSessionId()).isEqualTo(visit.getId());
        assertThat(saved.getCustomerSessionId()).isEqualTo(customers.findAll().get(0).getId());
        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(1).options().get(0).optionName()).isEqualTo("Extra cheese");
    }

    @Test
    void retryingWithTheSameKeyReturnsTheSameOrderAndCreatesNothingNew() throws Exception {
        String key = newKey();
        String firstJson = cart(line(pizza, "half", "[\"cheese\",\"jalapeno\"]", 1, "extra crispy"));
        String id = com.jayway.jsonpath.JsonPath.read(
                order(cookie, key, firstJson).andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString(), "$.id");

        // identical retry
        order(cookie, key, firstJson).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        // same intent written differently: add-ons reordered, note whitespace changed
        order(cookie, key, cart(line(pizza, "half", "[\"jalapeno\",\"cheese\"]", 1, "  extra   crispy ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void aRetryStillReturnsTheOriginalOrderAfterAMenuPriceChange() throws Exception {
        String key = newKey();
        String id = com.jayway.jsonpath.JsonPath.read(
                order(cookie, key, soupCart(1)).andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString(), "$.id");

        MenuItem item = items.findById(soup).orElseThrow();
        item.setPriceMinor(99000L);
        items.save(item);

        order(cookie, key, soupCart(1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.subtotalMinor").value(15000));
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentCartIsRefused() throws Exception {
        String key = newKey();
        order(cookie, key, soupCart(1)).andExpect(status().isCreated());

        order(cookie, key, soupCart(2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(orders.count()).isEqualTo(1);
        assertThat(orders.findAll().get(0).getSubtotalMinor()).isEqualTo(15000L);
    }

    @Test
    void differentKeysAreSeparateOrders() throws Exception {
        order(cookie, newKey(), soupCart(1)).andExpect(status().isCreated());
        order(cookie, newKey(), soupCart(1)).andExpect(status().isCreated());

        assertThat(orders.count()).isEqualTo(2);
    }

    @Test
    void keysAreScopedToTheCustomerWhoUsedThem() throws Exception {
        String other = newDiner();
        String key = newKey();

        order(cookie, key, soupCart(1)).andExpect(status().isCreated());
        order(other, key, soupCart(1)).andExpect(status().isCreated()).andExpect(jsonPath("$.customerLabel").value("Guest 2"));

        assertThat(orders.count()).isEqualTo(2);
        assertThat(orders.findAll()).extracting(Order::getCustomerSessionId).doesNotHaveDuplicates();
    }

    @Test
    void aMissingOrMalformedKeyIsRejected() throws Exception {
        for (String bad : new String[] {null, "", "short", "has spaces in it which is not allowed",
                "x".repeat(65), "bad/char/in/key/xxxxxx"}) {
            order(cookie, bad, soupCart(1))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(orders.count()).isZero();
    }

    @Test
    void tenSimultaneousSubmitsOfTheSameKeyCreateExactlyOneOrder() throws Exception {
        String key = newKey();
        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<int[]>> futures = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    var response = order(cookie, key, soupCart(1)).andReturn().getResponse();
                    synchronized (ids) {
                        ids.add(com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.id"));
                    }
                    return new int[] {response.getStatus()};
                }));
            }
            start.countDown();
            int created = 0;
            int replayed = 0;
            for (Future<int[]> future : futures) {
                int statusCode = future.get(30, TimeUnit.SECONDS)[0];
                if (statusCode == 201) {
                    created++;
                } else if (statusCode == 200) {
                    replayed++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(replayed).isEqualTo(attempts - 1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(orders.count()).isEqualTo(1);
        assertThat(ids).hasSize(attempts).containsOnly(orders.findAll().get(0).getId());
    }

    @Test
    void aFailedAttemptCreatesNothingAndDoesNotUseUpTheKey() throws Exception {
        MenuItem soldOut = items.findById(soup).orElseThrow();
        soldOut.setAvailable(false);
        items.save(soldOut);
        String key = newKey();

        order(cookie, key, soupCart(1)).andExpect(status().isConflict());
        order(cookie, key, cart(line(pizza, null, "[]", 1, null))).andExpect(status().isBadRequest());
        assertThat(orders.count()).isZero();

        // the customer fixes the cart and retries with the very same key
        order(cookie, key, cart(line(pizza, "full", "[]", 1, null))).andExpect(status().isCreated());
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void aCustomerCookieIsRequiredAndAClosedVisitStopsOrdering() throws Exception {
        order(null, newKey(), soupCart(1))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        order("garbage", newKey(), soupCart(1))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        DiningSession visit = sessions.findAll().get(0);
        visit.close(Instant.now());
        sessions.save(visit);

        order(cookie, newKey(), soupCart(1))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
        assertThat(orders.count()).isZero();
    }

    @Test
    void whileTheBillIsRequestedNewOrdersArePausedButEarlierOnesCanStillBeReplayed() throws Exception {
        String earlierKey = newKey();
        String id = com.jayway.jsonpath.JsonPath.read(
                order(cookie, earlierKey, soupCart(1)).andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString(), "$.id");

        String visitId = sessions.findAll().get(0).getId();
        mongo.updateFirst(Query.query(where("_id").is(visitId)), new Update().set("status", "BILL_REQUESTED"),
                DiningSession.class);

        order(cookie, newKey(), soupCart(1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        order(cookie, earlierKey, soupCart(1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        assertThat(orders.count()).isEqualTo(1);
    }
}