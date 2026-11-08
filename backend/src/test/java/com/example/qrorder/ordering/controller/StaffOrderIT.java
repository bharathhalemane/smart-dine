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
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.ordering.service.OrderActor;
import com.example.qrorder.ordering.service.OrderTransitionService;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class StaffOrderIT extends AbstractIntegrationTest {

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
    @Autowired OrderTransitionService engine;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String soup;
    String lassi;

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
        member(hotelId, "owner@example.com", "Owner Asha", HotelRole.OWNER);
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(hotelId, "waiter@example.com", "Waiter Wasim", HotelRole.WAITER);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(otherHotelId, "othercook@example.com", "Other Cook", HotelRole.KITCHEN);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
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

    private String orderUrl(String hotel, String orderId, String suffix) {
        return "/api/v1/hotels/" + hotel + "/kitchen/orders/" + orderId + (suffix == null ? "" : suffix);
    }

    private ResultActions cancel(String bearer, String orderId, String json) throws Exception {
        return call(post(orderUrl(hotelId, orderId, "/cancel")), bearer, json);
    }

    /** Soup x1 (15000), Lassi x1 (6000); subtotal 21000. */
    private String orderIn(OrderStatus status) {
        List<OrderLine> lines = List.of(
                new OrderLine(soup, "Soup", Diet.VEG, null, null, List.of(), 15000L, 1, 15000L, null),
                new OrderLine(lassi, "Lassi", Diet.VEG, null, null, List.of(), 6000L, 1, 6000L, null));
        String id = orders.save(new Order(hotelId, "ds-1", "t-1", "T1", "cust-1", "Guest 1", "INR", lines, 21000L,
                null, null, Instant.now())).getId();
        if (status != OrderStatus.PENDING) {
            mongo.updateFirst(Query.query(where("_id").is(id)), new Update().set("status", status), Order.class);
        }
        return id;
    }

    private Order stored(String id) {
        return orders.findById(id).orElseThrow();
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
    void staffCancelAnOrderWithAReasonAndItIsRecordedWithTheirName() throws Exception {
        String id = orderIn(OrderStatus.PREPARING);

        cancel(login("cook@example.com"), id, "{\"reason\":\"  Kitchen equipment   failure \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("STAFF"))
                .andExpect(jsonPath("$.cancelReason").value("Kitchen equipment failure"))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].from").value("PREPARING"))
                .andExpect(jsonPath("$.history[0].to").value("CANCELLED"))
                .andExpect(jsonPath("$.history[0].actorType").value("STAFF"))
                .andExpect(jsonPath("$.history[0].actorName").value("Cook Ravi"))
                .andExpect(jsonPath("$.history[0].reason").value("Kitchen equipment failure"));

        Order order = stored(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
        assertThat(order.getStatusHistory().get(0).actorId()).isEqualTo(userId("cook@example.com"));
    }

    @Test
    void theDinerIsToldWhoCancelledAndWhy() throws Exception {
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-t1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        Cookie diner = new Cookie("qr_customer", matcher.group(1));

        String byStaff = placeAsDiner(diner);
        String byDiner = placeAsDiner(diner);

        cancel(login("waiter@example.com"), byStaff, "{\"reason\":\"Closing early\"}").andExpect(status().isOk());
        mvc.perform(post("/api/v1/customer/orders/" + byDiner + "/cancel").cookie(diner)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/customer/orders/" + byStaff).cookie(diner))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("STAFF"))
                .andExpect(jsonPath("$.cancelReason").value("Closing early"));
        mvc.perform(get("/api/v1/customer/orders/" + byDiner).cookie(diner))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.cancelReason").doesNotExist());
    }

    private String placeAsDiner(Cookie diner) throws Exception {
        String body = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(soup))
                        .cookie(diner).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void aReasonIsRequiredAndMustBeClean() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String token = login("manager@example.com");

        cancel(token, id, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String bad : List.of("{}", "{\"reason\":null}", "{\"reason\":\"   \"}",
                "{\"reason\":\"%s\"}".formatted("x".repeat(101)), "{\"reason\":\"bad\\u0007reason\"}")) {
            cancel(token, id, bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored(id).getStatusHistory()).isEmpty();
    }

    @Test
    void anOrderCanBeCancelledWhileInProgressButNotOnceReadyOrDelivered() throws Exception {
        String token = login("manager@example.com");
        for (OrderStatus open : List.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING)) {
            String id = orderIn(open);
            cancel(token, id, "{\"reason\":\"Customer left\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            assertThat(stored(id).getStatusHistory().get(0).from()).isEqualTo(open);
        }

        for (OrderStatus closed : List.of(OrderStatus.READY, OrderStatus.DELIVERED)) {
            String id = orderIn(closed);
            cancel(token, id, "{\"reason\":\"Too late\"}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
            assertThat(stored(id).getStatus()).isEqualTo(closed);
            assertThat(stored(id).getCancelReason()).isNull();
        }

        // cancelling again keeps the first reason and adds nothing to the history
        String again = orderIn(OrderStatus.PENDING);
        cancel(token, again, "{\"reason\":\"First reason\"}").andExpect(status().isOk());
        cancel(token, again, "{\"reason\":\"Second reason\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason").value("First reason"));
        assertThat(stored(again).getStatusHistory()).hasSize(1);
    }

    @Test
    void kitchenAndWaitersMayCancelButCashiersAndOutsidersMayNot() throws Exception {
        String forCook = orderIn(OrderStatus.PENDING);
        String forWaiter = orderIn(OrderStatus.PENDING);
        String untouched = orderIn(OrderStatus.PENDING);
        String body = "{\"reason\":\"Test\"}";

        cancel(login("cashier@example.com"), untouched, body).andExpect(status().isForbidden());
        cancel(null, untouched, body).andExpect(status().isUnauthorized());
        cancel(login("othercook@example.com"), untouched, body).andExpect(status().isNotFound());
        call(post(orderUrl(otherHotelId, untouched, "/cancel")), login("othercook@example.com"), body)
                .andExpect(status().isNotFound());
        cancel(login("cook@example.com"), "does-not-exist", body).andExpect(status().isNotFound());
        assertThat(stored(untouched).getStatus()).isEqualTo(OrderStatus.PENDING);

        cancel(login("cook@example.com"), forCook, body).andExpect(status().isOk());
        cancel(login("waiter@example.com"), forWaiter, body).andExpect(status().isOk());
        assertThat(stored(forCook).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stored(forWaiter).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void theStaffViewShowsTheWholeHistoryWithNamesAndLineReports() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String cook = login("cook@example.com");
        call(post(orderUrl(hotelId, id, "/accept")), cook, null).andExpect(status().isOk());
        call(post(orderUrl(hotelId, id, "/start")), cook, null).andExpect(status().isOk());
        call(post(orderUrl(hotelId, id, "/lines/1/unavailable")), login("manager@example.com"),
                "{\"reason\":\"No lassi\",\"alsoMarkMenuItem\":false}").andExpect(status().isOk());

        call(get(orderUrl(hotelId, id, null)), login("waiter@example.com"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.tableNumber").value("T1"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.subtotalMinor").value(15000))
                .andExpect(jsonPath("$.lines[1].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].to").value("ACCEPTED"))
                .andExpect(jsonPath("$.history[0].actorName").value("Cook Ravi"))
                .andExpect(jsonPath("$.history[1].to").value("PREPARING"))
                .andExpect(jsonPath("$.history[1].actorName").value("Cook Ravi"))
                .andExpect(jsonPath("$.lineReports.length()").value(1))
                .andExpect(jsonPath("$.lineReports[0].itemName").value("Lassi"))
                .andExpect(jsonPath("$.lineReports[0].reportedByName").value("Manager Meera"))
                .andExpect(jsonPath("$.lineReports[0].reason").value("No lassi"));

        call(get(orderUrl(hotelId, id, null)), login("owner@example.com"), null).andExpect(status().isOk());
        call(get(orderUrl(hotelId, id, null)), login("cashier@example.com"), null).andExpect(status().isForbidden());
        call(get(orderUrl(hotelId, id, null)), null, null).andExpect(status().isUnauthorized());
        call(get(orderUrl(hotelId, id, null)), login("othercook@example.com"), null).andExpect(status().isNotFound());
        call(get(orderUrl(hotelId, "does-not-exist", null)), cook, null).andExpect(status().isNotFound());
    }

    @Test
    void aCustomerActionIsShownWithTheGuestLabel() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        engine.transition(Criteria.where("id").is(id).and("customerSessionId").is("cust-1"),
                OrderStatus.PENDING, OrderStatus.CANCELLED, OrderActor.customer("cust-1"), null);

        call(get(orderUrl(hotelId, id, null)), login("manager@example.com"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.history[0].actorType").value("CUSTOMER"))
                .andExpect(jsonPath("$.history[0].actorName").value("Guest 1"));
    }

    @Test
    void aCancelRacingAnAcceptAlwaysEndsCancelledAndTheHistoryTellsWhatHappened() throws Exception {
        String cook = login("cook@example.com");
        String manager = login("manager@example.com");
        for (int round = 0; round < 10; round++) {
            String id = orderIn(OrderStatus.PENDING);

            List<Callable<Integer>> tasks = List.of(
                    () -> call(post(orderUrl(hotelId, id, "/accept")), cook, null).andReturn().getResponse().getStatus(),
                    () -> cancel(manager, id, "{\"reason\":\"Racing\"}").andReturn().getResponse().getStatus());
            List<Integer> statuses = runTogether(tasks);

            Order order = stored(id);
            assertThat(statuses.get(1)).as("round %d: the cancel always lands", round).isEqualTo(200);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            if (statuses.get(0) == 200) { // accept got in first, then the cancel applied on top
                assertThat(order.getStatusHistory()).extracting(c -> c.to())
                        .containsExactly(OrderStatus.ACCEPTED, OrderStatus.CANCELLED);
            } else { // cancel got in first, so the accept was refused
                assertThat(statuses.get(0)).isEqualTo(409);
                assertThat(order.getStatusHistory()).extracting(c -> c.to()).containsExactly(OrderStatus.CANCELLED);
            }
        }
    }
}