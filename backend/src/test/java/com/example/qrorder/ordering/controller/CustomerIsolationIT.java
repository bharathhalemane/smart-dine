package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.CustomerSession;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
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
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CustomerIsolationIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;
    @Autowired MenuCategoryRepository categories;
    @Autowired MenuItemRepository items;
    @Autowired OrderRepository orders;
    @Autowired PasswordEncoder encoder;

    String hotelA;
    String hotelB;
    String tableA1;
    String tableA2;
    String soupA;
    String soupB;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        categories.deleteAll();
        items.deleteAll();
        orders.deleteAll();
        Instant now = Instant.now();

        hotelA = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        hotelB = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR", now)).getId();

        tableA1 = seedTable(hotelA, "T1", "tok-a1");
        tableA2 = seedTable(hotelA, "T2", "tok-a2");
        seedTable(hotelB, "T1", "tok-b1");

        String catA = categories.save(new MenuCategory(hotelA, "Menu", null, 1, now)).getId();
        String catB = categories.save(new MenuCategory(hotelB, "Menu", null, 1, now)).getId();
        soupA = items.save(new MenuItem(hotelA, catA, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        soupB = items.save(new MenuItem(hotelB, catB, "Dal", null, 12000L, Diet.VEG, 0, now)).getId();
    }

    // ---------- helpers ----------

    private String seedTable(String hotel, String number, String plainToken) {
        String id = tables.save(new DiningTable(hotel, number, 4, null, Instant.now())).getId();
        credentials.save(new QrCredential(hotel, id, Tokens.sha256(plainToken), "u", Instant.now()));
        return id;
    }

    /** Scans the given QR as a brand-new diner and returns that diner's cookie token. */
    private String newDiner(String qrToken) throws Exception {
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(qrToken)))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String cartJson(String itemId) {
        return "{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(itemId);
    }

    private MvcResult placeRaw(String cookie, String itemId) throws Exception {
        return mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(cartJson(itemId)).cookie(new Cookie("qr_customer", cookie))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
    }

    private String place(String cookie, String itemId) throws Exception {
        return JsonPath.read(placeRaw(cookie, itemId).getResponse().getContentAsString(), "$.id");
    }

    private Order stored(String orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    /** Every route a diner can call, built fresh each time (the builders are mutable). */
    private List<MockHttpServletRequestBuilder> routes(String orderId) {
        return List.of(
                get("/api/v1/customer/me"),
                put("/api/v1/customer/me/name").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Anita\"}"),
                post("/api/v1/customer/cart/price").contentType(MediaType.APPLICATION_JSON).content(cartJson(soupA)),
                post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON).content(cartJson(soupA))
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                get("/api/v1/customer/orders"),
                get("/api/v1/customer/orders/" + orderId),
                post("/api/v1/customer/orders/" + orderId + "/cancel"));
    }

    private void assertEveryRoute(String cookie, String orderId, int expectedStatus, String expectedCode)
            throws Exception {
        for (MockHttpServletRequestBuilder route : routes(orderId)) {
            if (cookie != null) {
                route.cookie(new Cookie("qr_customer", cookie));
            }
            mvc.perform(route).andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedCode));
        }
    }

    private String ownerToken(String hotel) throws Exception {
        User user = new User("owner-" + hotel + "@example.com", encoder.encode(PASSWORD), "Owner");
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(HotelRole.OWNER), user.getId(), Instant.now()));
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- tests ----------

    @Test
    void everyCustomerRouteRequiresACookie() throws Exception {
        String anita = newDiner("tok-a1");
        String orderId = place(anita, soupA);

        assertEveryRoute(null, orderId, 401, "UNAUTHENTICATED");
        assertEveryRoute("not-a-real-cookie", orderId, 401, "UNAUTHENTICATED");
    }

    @Test
    void forgedAndMalformedCookiesNeverAuthenticate() throws Exception {
        String anita = newDiner("tok-a1");
        CustomerSession stored = customers.findAll().get(0);

        List<String> forged = List.of(
                anita.substring(0, anita.length() - 1) + (anita.endsWith("A") ? "B" : "A"), // one character off
                stored.getTokenHash(),   // what the database holds must not work as a credential
                stored.getId(),          // nor the customer's own id
                stored.getDiningSessionId(),
                "x".repeat(10_000),
                " ");
        for (String cookie : forged) {
            mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", cookie)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        // the genuine one still works
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", anita))).andExpect(status().isOk());
    }

    @Test
    void aClosedTableKillsEveryCustomerRouteAndARescanStartsCleanly() throws Exception {
        String old = newDiner("tok-a1");
        String oldOrder = place(old, soupA);

        DiningSession visit = sessions.findAll().get(0);
        visit.close(Instant.now());
        sessions.save(visit);

        assertEveryRoute(old, oldOrder, 401, "SESSION_ENDED");
        assertThat(stored(oldOrder).getStatus()).isEqualTo(OrderStatus.PENDING); // nothing changed behind the 401

        // scanning again opens a new visit with a new identity that sees none of the old one
        String fresh = newDiner("tok-a1");
        assertThat(fresh).isNotEqualTo(old);
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", fresh)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guestNumber").value(1));
        mvc.perform(get("/api/v1/customer/orders").cookie(new Cookie("qr_customer", fresh)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/customer/orders/" + oldOrder).cookie(new Cookie("qr_customer", fresh)))
                .andExpect(status().isNotFound());
        assertEveryRoute(old, oldOrder, 401, "SESSION_ENDED"); // the old cookie stays dead
    }

    @Test
    void aSuspendedRestaurantStopsCustomersUntilItIsReactivated() throws Exception {
        String anita = newDiner("tok-a1");
        String orderId = place(anita, soupA);

        Hotel hotel = hotels.findById(hotelA).orElseThrow();
        hotel.setStatus(HotelStatus.SUSPENDED);
        hotels.save(hotel);

        assertEveryRoute(anita, orderId, 401, "SESSION_ENDED");
        assertThat(orders.count()).isEqualTo(1); // the blocked POST /orders created nothing
        assertThat(stored(orderId).getStatus()).isEqualTo(OrderStatus.PENDING);

        hotel.setStatus(HotelStatus.ACTIVE);
        hotels.save(hotel);

        mvc.perform(get("/api/v1/customer/orders/" + orderId).cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(orderId));
    }

    @Test
    void dinersAtDifferentTablesAndHotelsCannotSeeEachOthersOrders() throws Exception {
        String atA1 = newDiner("tok-a1");
        String atA2 = newDiner("tok-a2");
        String atB1 = newDiner("tok-b1");
        String orderA1 = place(atA1, soupA);
        String orderA2 = place(atA2, soupA);
        String orderB1 = place(atB1, soupB);

        // each sees exactly one order: their own
        for (String[] pair : new String[][] {{atA1, orderA1}, {atA2, orderA2}, {atB1, orderB1}}) {
            mvc.perform(get("/api/v1/customer/orders").cookie(new Cookie("qr_customer", pair[0])))
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(pair[1]));
        }

        // nobody can read or cancel another table's or another hotel's order
        String[][] strangers = {{atA2, orderA1}, {atB1, orderA1}, {atA1, orderB1}, {atA1, orderA2}};
        for (String[] pair : strangers) {
            mvc.perform(get("/api/v1/customer/orders/" + pair[1]).cookie(new Cookie("qr_customer", pair[0])))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/customer/orders/" + pair[1] + "/cancel")
                            .cookie(new Cookie("qr_customer", pair[0])))
                    .andExpect(status().isNotFound());
        }
        for (String id : List.of(orderA1, orderA2, orderB1)) {
            assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        // a diner of one hotel cannot order or price another hotel's dish
        mvc.perform(post("/api/v1/customer/cart/price").contentType(MediaType.APPLICATION_JSON)
                        .content(cartJson(soupB)).cookie(new Cookie("qr_customer", atA1)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(cartJson(soupB)).cookie(new Cookie("qr_customer", atA1))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
        assertThat(orders.count()).isEqualTo(3);
    }

    @Test
    void customerResponsesNeverLeakInternalIdentifiers() throws Exception {
        String anita = newDiner("tok-a1");
        CustomerSession customer = customers.findAll().get(0);

        List<String> bodies = new ArrayList<>();
        MvcResult placed = placeRaw(anita, soupA);
        String orderId = JsonPath.read(placed.getResponse().getContentAsString(), "$.id");
        bodies.add(placed.getResponse().getContentAsString());
        for (MockHttpServletRequestBuilder route : routes(orderId)) {
            route.cookie(new Cookie("qr_customer", anita));
            bodies.add(mvc.perform(route).andReturn().getResponse().getContentAsString());
        }

        List<String> forbidden = List.of(
                hotelA, tableA1, customer.getId(), customer.getDiningSessionId(), customer.getTokenHash(), anita,
                "hotelId", "tableId", "diningSessionId", "customerSessionId", "idempotencyKey", "requestHash",
                "tokenHash", "version");
        for (String body : bodies) {
            for (String secret : forbidden) {
                assertThat(body).as("response must not contain '%s'", secret).doesNotContain(secret);
            }
        }
        assertThat(bodies).allMatch(body -> !body.isBlank());
    }

    @Test
    void staffAndCustomerCredentialsAreNotInterchangeable() throws Exception {
        String owner = ownerToken(hotelA);
        String anita = newDiner("tok-a1");
        place(anita, soupA);

        // a staff login is not a diner identity
        mvc.perform(get("/api/v1/customer/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/customer/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isUnauthorized());

        // a diner cookie is not a staff login
        mvc.perform(get("/api/v1/hotels/" + hotelA + "/menu/items").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/hotels/" + hotelA + "/floor").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isUnauthorized());

        // and the QR token is not a cookie
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", "tok-a1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aGoneQrDoesNotAffectAnActiveVisitButBlocksNewScans() throws Exception {
        String anita = newDiner("tok-a1");
        credentials.deleteAll(); // stands in for the table's QR being rotated or revoked

        place(anita, soupA); // the diner already seated is unaffected
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", anita))).andExpect(status().isOk());

        MvcResult rescan = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-a1\"}"))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(rescan.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(customers.count()).isEqualTo(1);
    }

    @Test
    void tablesOrderingAtTheSameTimeDoNotInterfere() throws Exception {
        List<String> atA1 = new ArrayList<>();
        List<String> atA2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            atA1.add(newDiner("tok-a1"));
            atA2.add(newDiner("tok-a2"));
        }

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (String cookie : concat(atA1, atA2)) {
            tasks.add(() -> mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                            .content(cartJson(soupA)).cookie(new Cookie("qr_customer", cookie))
                            .header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andReturn().getResponse().getStatus());
        }
        assertThat(runTogether(tasks)).containsOnly(201);

        assertThat(orders.count()).isEqualTo(8);
        for (String table : List.of(tableA1, tableA2)) {
            DiningSession visit = sessions.findAll().stream().filter(s -> s.getTableId().equals(table)).findFirst()
                    .orElseThrow();
            long ordersAtTable = orders.findAll().stream()
                    .filter(o -> o.getDiningSessionId().equals(visit.getId())).count();
            assertThat(ordersAtTable).isEqualTo(4);
            assertThat(visit.getOrderCount()).isEqualTo(4);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}