package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.ordering.model.LineStatus;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ItemUnavailableIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
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

    String hotelId;
    String otherHotelId;
    String soup;    // 15000
    String paneer;  // 28000
    String lassi;   // 6000

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

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR", now)).getId();
        member(hotelId, "owner@example.com", HotelRole.OWNER);
        member(hotelId, "manager@example.com", HotelRole.MANAGER);
        member(hotelId, "cook@example.com", HotelRole.KITCHEN);
        member(hotelId, "waiter@example.com", HotelRole.WAITER);
        member(hotelId, "cashier@example.com", HotelRole.CASHIER);
        member(otherHotelId, "othercook@example.com", HotelRole.KITCHEN);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        paneer = items.save(new MenuItem(hotelId, category, "Paneer Tikka", null, 28000L, Diet.VEG, 1, now)).getId();
        lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 2, now)).getId();
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String userId(String email) {
        return users.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String lineUrl(String hotel, String orderId, String lineNo) {
        return "/api/v1/hotels/" + hotel + "/kitchen/orders/" + orderId + "/lines/" + lineNo + "/unavailable";
    }

    private String lineUrl(String orderId, int lineNo) {
        return lineUrl(hotelId, orderId, String.valueOf(lineNo));
    }

    /** An order of three lines: Soup x1 (15000), Paneer x2 (56000), Lassi x1 (6000); subtotal 77000. */
    private String orderIn(OrderStatus status) {
        List<OrderLine> lines = List.of(
                new OrderLine(soup, "Soup", Diet.VEG, null, null, List.of(), 15000L, 1, 15000L, null),
                new OrderLine(paneer, "Paneer Tikka", Diet.VEG, null, null, List.of(), 28000L, 2, 56000L, "less spicy"),
                new OrderLine(lassi, "Lassi", Diet.VEG, null, null, List.of(), 6000L, 1, 6000L, null));
        String id = orders.save(new Order(hotelId, "ds-1", "t-1", "T1", "cust-1", "Guest 1", "INR", lines, 77000L,
                null, null, Instant.now())).getId();
        if (status != OrderStatus.PENDING) {
            mongo.updateFirst(Query.query(where("_id").is(id)), new Update().set("status", status), Order.class);
        }
        return id;
    }

    private Order stored(String id) {
        return orders.findById(id).orElseThrow();
    }

    private boolean menuAvailable(String itemId) {
        return items.findById(itemId).orElseThrow().isAvailable();
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
    void theKitchenTakesOneLineOffAndTheRestOfTheOrderCarriesOn() throws Exception {
        String id = orderIn(OrderStatus.PENDING);

        call(post(lineUrl(id, 1)), login("cook@example.com"), "{\"reason\":\"Out of paneer\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lines[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.lines[1].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.lines[1].statusNote").value("Out of paneer"))
                .andExpect(jsonPath("$.lines[2].status").value("ACTIVE"));

        Order order = stored(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSubtotalMinor()).isEqualTo(21000L); // soup + lassi
        assertThat(order.getLines().get(1).lineTotalMinor()).isEqualTo(56000L); // the original stays on the line
        assertThat(order.getLineReports()).hasSize(1);
        assertThat(order.getLineReports().get(0).lineNo()).isEqualTo(1);
        assertThat(order.getLineReports().get(0).reportedBy()).isEqualTo(userId("cook@example.com"));
        assertThat(order.getLineReports().get(0).reason()).isEqualTo("Out of paneer");
        assertThat(menuAvailable(paneer)).isFalse(); // switched off for new orders by default
        assertThat(menuAvailable(soup)).isTrue();
        assertThat(menuAvailable(lassi)).isTrue();
    }

    @Test
    void theMenuItemCanBeLeftAloneOnRequest() throws Exception {
        String id = orderIn(OrderStatus.ACCEPTED);

        call(post(lineUrl(id, 1)), login("cook@example.com"), "{\"alsoMarkMenuItem\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[1].status").value("UNAVAILABLE"));

        assertThat(menuAvailable(paneer)).isTrue();
        assertThat(stored(id).getSubtotalMinor()).isEqualTo(21000L);
    }

    @Test
    void reportingTheSameLineAgainChangesNothing() throws Exception {
        String id = orderIn(OrderStatus.PREPARING);
        String token = login("cook@example.com");

        call(post(lineUrl(id, 0)), token, "{\"reason\":\"No stock\"}").andExpect(status().isOk());
        call(post(lineUrl(id, 0)), token, "{\"reason\":\"Different words\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].statusNote").value("No stock"));
        call(post(lineUrl(id, 0)), token, null).andExpect(status().isOk());

        Order order = stored(id);
        assertThat(order.getLineReports()).hasSize(1);
        assertThat(order.getSubtotalMinor()).isEqualTo(62000L);
    }

    @Test
    void whenEveryLineIsUnavailableTheOrderCancelsItself() throws Exception {
        String id = orderIn(OrderStatus.ACCEPTED);
        String token = login("cook@example.com");

        call(post(lineUrl(id, 0)), token, "{\"alsoMarkMenuItem\":false}")
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        call(post(lineUrl(id, 1)), token, "{\"alsoMarkMenuItem\":false}")
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        call(post(lineUrl(id, 2)), token, "{\"alsoMarkMenuItem\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        Order order = stored(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getSubtotalMinor()).isZero();
        assertThat(order.getCancelledBy()).isEqualTo("STAFF");
        assertThat(order.getCancelledAt()).isNotNull();
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).from()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(order.getStatusHistory().get(0).to()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusHistory().get(0).actorId()).isEqualTo(userId("cook@example.com"));
        assertThat(order.getStatusHistory().get(0).reason()).isEqualTo("All items unavailable");
        assertThat(order.getLines()).allMatch(line -> line.status() == LineStatus.UNAVAILABLE);

        // a double-tap on the last line after the auto-cancel is still a harmless 200
        call(post(lineUrl(id, 2)), token, null).andExpect(status().isOk());
    }

    @Test
    void itemsCanOnlyBeTakenOffWhileTheKitchenStillHasWorkToDo() throws Exception {
        String ready = orderIn(OrderStatus.READY);
        String delivered = orderIn(OrderStatus.DELIVERED);
        String cancelled = orderIn(OrderStatus.CANCELLED);
        String token = login("manager@example.com");

        call(post(lineUrl(ready, 0)), token, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        call(post(lineUrl(delivered, 0)), token, null).andExpect(status().isConflict());
        call(post(lineUrl(cancelled, 0)), token, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This order has been cancelled"));

        for (String id : List.of(ready, delivered, cancelled)) {
            assertThat(stored(id).getLines()).allMatch(OrderLine::stillOrdered);
            assertThat(stored(id).getSubtotalMinor()).isEqualTo(77000L);
        }
        assertThat(menuAvailable(soup)).isTrue(); // refused requests never touch the menu
    }

    @Test
    void badLineNumbersAndUnknownOrdersAreNotFound() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String token = login("cook@example.com");

        for (String bad : List.of("3", "99", "-1", "abc", "1.5")) {
            call(post(lineUrl(hotelId, id, bad)), token, null).andExpect(status().isNotFound());
        }
        call(post(lineUrl(hotelId, "does-not-exist", "0")), token, null).andExpect(status().isNotFound());

        assertThat(stored(id).getLines()).allMatch(OrderLine::stillOrdered);
        assertThat(menuAvailable(soup)).isTrue();
    }

    @Test
    void onlyKitchenManagerAndOwnerMayReportAndOnlyInTheirOwnHotel() throws Exception {
        String id = orderIn(OrderStatus.PENDING);

        call(post(lineUrl(id, 0)), login("waiter@example.com"), null).andExpect(status().isForbidden());
        call(post(lineUrl(id, 0)), login("cashier@example.com"), null).andExpect(status().isForbidden());
        call(post(lineUrl(id, 0)), null, null).andExpect(status().isUnauthorized());
        call(post(lineUrl(id, 0)), login("othercook@example.com"), null).andExpect(status().isNotFound());
        call(post(lineUrl(otherHotelId, id, "0")), login("othercook@example.com"), null).andExpect(status().isNotFound());
        assertThat(stored(id).getLines()).allMatch(OrderLine::stillOrdered);
        assertThat(menuAvailable(soup)).isTrue();

        call(post(lineUrl(id, 0)), login("manager@example.com"), null).andExpect(status().isOk());
        call(post(lineUrl(id, 2)), login("owner@example.com"), null).andExpect(status().isOk());
    }

    @Test
    void theDinerSeesTheUnavailableLineTheNewTotalAndTheMenuStopsOfferingIt() throws Exception {
        // a real order placed by a real diner
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-t1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        Cookie diner = new Cookie("qr_customer", matcher.group(1));
        String body = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1},{\"itemId\":\"%s\",\"quantity\":1}]}"
                                .formatted(soup, lassi))
                        .cookie(diner).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(body, "$.id");

        call(post(lineUrl(orderId, 1)), login("cook@example.com"), "{\"reason\":\"Machine is broken\"}")
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/customer/orders/" + orderId).cookie(diner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lines[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.lines[1].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.lines[1].statusNote").value("Machine is broken"))
                .andExpect(jsonPath("$.subtotalMinor").value(15000));
        mvc.perform(get("/api/v1/public/hotels/" + hotelId + "/menu"))
                .andExpect(jsonPath("$.categories[0].items[?(@.name=='Lassi')].available").value(false))
                .andExpect(jsonPath("$.categories[0].items[?(@.name=='Soup')].available").value(true));
        // and nobody can order it now
        mvc.perform(post("/api/v1/customer/cart/price").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(lassi)).cookie(diner))
                .andExpect(status().isConflict());
    }

    @Test
    void reportsOnDifferentLinesAndAConcurrentAcceptAllTakeEffect() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String cook = login("cook@example.com");
        String manager = login("manager@example.com");
        String kitchenUrl = "/api/v1/hotels/" + hotelId + "/kitchen/orders/" + id + "/accept";

        List<Callable<Integer>> tasks = List.of(
                () -> call(post(lineUrl(id, 0)), cook, "{\"alsoMarkMenuItem\":false}").andReturn().getResponse().getStatus(),
                () -> call(post(lineUrl(id, 1)), manager, "{\"alsoMarkMenuItem\":false}").andReturn().getResponse().getStatus(),
                () -> call(post(kitchenUrl), cook, null).andReturn().getResponse().getStatus());
        assertThat(runTogether(tasks)).containsOnly(200);

        Order order = stored(id);
        assertThat(order.getLines().get(0).status()).isEqualTo(LineStatus.UNAVAILABLE);
        assertThat(order.getLines().get(1).status()).isEqualTo(LineStatus.UNAVAILABLE);
        assertThat(order.getLines().get(2).status()).isEqualTo(LineStatus.ACTIVE);
        assertThat(order.getSubtotalMinor()).isEqualTo(6000L);
        assertThat(order.getLineReports()).hasSize(2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(order.getStatusHistory()).hasSize(1);
    }
}