package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.dining.service.SessionOrderGate;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.ordering.service.OrderWriter;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class OrderSessionRaceIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");
    private static final String PASSWORD = "correct-horse-battery";

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
    @Autowired SessionOrderGate gate;
    @Autowired OrderWriter writer;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String tableId;
    String soup;

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
        tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String starters = categories.save(new MenuCategory(hotelId, "Starters", null, 1, now)).getId();
        soup = items.save(new MenuItem(hotelId, starters, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
    }

    // ---------- helpers ----------

    /** Scans as a brand-new diner; after a close this opens a fresh visit. Returns the diner's cookie token. */
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

    private String soupCart() {
        return "{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(soup);
    }

    private ResultActions order(String cookie, String key) throws Exception {
        return mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                .content(soupCart()).cookie(new Cookie("qr_customer", cookie)).header("Idempotency-Key", key));
    }

    private DiningSession activeVisit() {
        return sessions.findAll().stream().filter(DiningSession::isActive).findFirst().orElseThrow();
    }

    private DiningSession reload(String visitId) {
        return sessions.findById(visitId).orElseThrow();
    }

    private long ordersOf(String visitId) {
        return orders.findAll().stream().filter(o -> o.getDiningSessionId().equals(visitId)).count();
    }

    private String ownerToken() throws Exception {
        User user = new User("owner@example.com", encoder.encode(PASSWORD), "Owner");
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(HotelRole.OWNER), user.getId(), Instant.now()));
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** Runs all tasks at the same instant and returns their results. */
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

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo(expected));
    }

    // ---------- tests ----------

    @Test
    void theGateAdmitsOnlyIntoAnOpenSessionAndCountsWhatItAdmits() throws Exception {
        newDiner();
        String visitId = activeVisit().getId();

        gate.admitOrder(hotelId, visitId);
        gate.admitOrder(hotelId, visitId);
        assertThat(reload(visitId).getOrderCount()).isEqualTo(2);

        assertCode(() -> gate.admitOrder("another-hotel", visitId), ErrorCode.SESSION_ENDED);
        assertCode(() -> gate.admitOrder(hotelId, "no-such-session"), ErrorCode.SESSION_ENDED);

        mongo.updateFirst(Query.query(where("_id").is(visitId)), new Update().set("status", "BILL_REQUESTED"),
                DiningSession.class);
        assertCode(() -> gate.admitOrder(hotelId, visitId), ErrorCode.CONFLICT);

        mongo.updateFirst(Query.query(where("_id").is(visitId)),
                new Update().set("status", "CLOSED").set("active", false), DiningSession.class);
        assertCode(() -> gate.admitOrder(hotelId, visitId), ErrorCode.SESSION_ENDED);

        assertThat(reload(visitId).getOrderCount()).isEqualTo(2); // refusals counted nothing
    }

    @Test
    void aFailedInsertRollsBackTheGateSoTheCountStaysHonest() throws Exception {
        newDiner();
        String visitId = activeVisit().getId();
        String key = "rollback-key-0123456789";

        writer.insertIntoOpenSession(new Order(hotelId, visitId, tableId, "T1", "cust-x", "Guest 1", "INR",
                List.of(), 0L, key, "hash", Instant.now()));
        assertThat(reload(visitId).getOrderCount()).isEqualTo(1);

        // same customer + same key: the unique index rejects the insert AFTER the gate already incremented
        assertThatThrownBy(() -> writer.insertIntoOpenSession(new Order(hotelId, visitId, tableId, "T1", "cust-x",
                "Guest 1", "INR", List.of(), 0L, key, "hash", Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(reload(visitId).getOrderCount()).isEqualTo(1);
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void ordersFromSeveralDinersAtOneTableAllSucceedDespiteContention() throws Exception {
        List<String> diners = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            diners.add(newDiner());
        }
        String visitId = activeVisit().getId();

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (String diner : diners) {
            tasks.add(() -> order(diner, newKey()).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runTogether(tasks);

        assertThat(statuses).containsOnly(201);
        assertThat(ordersOf(visitId)).isEqualTo(6);
        assertThat(reload(visitId).getOrderCount()).isEqualTo(6);
    }

    @Test
    void aFloodOfIdenticalSubmitsLeavesExactlyOneOrderAndOneCount() throws Exception {
        String diner = newDiner();
        String visitId = activeVisit().getId();
        String key = newKey();

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> order(diner, key).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runTogether(tasks);

        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(ordersOf(visitId)).isEqualTo(1);
        assertThat(reload(visitId).getOrderCount()).isEqualTo(1);
    }

    @Test
    void ordersRacingAgainstAClosingTableAreEitherFullyAcceptedOrFullyRefused() throws Exception {
        String token = ownerToken();

        for (int round = 0; round < 4; round++) {
            String diner = newDiner(); // after the previous round's close this opens a fresh visit
            String visitId = activeVisit().getId();

            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(() -> order(diner, newKey()).andReturn().getResponse().getStatus());
            }
            tasks.add(() -> mvc.perform(post("/api/v1/hotels/" + hotelId + "/sessions/" + visitId + "/close")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn().getResponse().getStatus());
            List<Integer> statuses = runTogether(tasks);

            List<Integer> orderStatuses = statuses.subList(0, 10);
            assertThat(orderStatuses).as("round %d: an order is accepted, refused or told to retry, never a 5xx", round)
                    .allMatch(s -> s == 201 || s == 401 || s == 409);
            assertThat(statuses.get(10)).as("the close itself must succeed").isEqualTo(200);

            long accepted = orderStatuses.stream().filter(s -> s == 201).count();
            DiningSession visit = reload(visitId);
            assertThat(visit.isActive()).isFalse();
            assertThat(ordersOf(visitId)).as("every accepted order is stored, nothing else").isEqualTo(accepted);
            assertThat(visit.getOrderCount()).isEqualTo((int) accepted);

            // once the table is closed nothing more gets in
            order(diner, newKey()).andExpect(status().isUnauthorized());
            assertThat(ordersOf(visitId)).isEqualTo(accepted);
        }
    }
}