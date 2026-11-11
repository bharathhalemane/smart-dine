package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
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
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
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
import java.time.Duration;
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
import org.junit.jupiter.api.RepeatedTest;
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

/**
 * Races that cross modules. Every test asserts what must be true whichever side wins, so none of them depends on
 * timing luck: the outcome may differ between runs, the invariants may not.
 */
class ConcurrencyCasesIT extends AbstractIntegrationTest {

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
    @Autowired BillRepository bills;
    @Autowired PaymentRepository payments;
    @Autowired PaymentSettingsRepository paymentSettings;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String soup;
    String anita;
    String orderA; // Soup, 15000, the older order
    String orderB; // Lassi x2, 12000
    String sessionBase;

    @BeforeEach
    void seed() throws Exception {
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
        bills.deleteAll();
        payments.deleteAll();
        paymentSettings.deleteAll();
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        member("owner@example.com", "Owner Om", HotelRole.OWNER);
        member("manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member("cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member("cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        String lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();

        anita = newDiner();
        call(put("/api/v1/hotels/" + hotelId + "/payments/settings"), "owner@example.com",
                "{\"enabledMethods\":[\"CASH\"],\"partialPaymentsAllowed\":true,\"paymentTiming\":\"BEFORE_FOOD\"}")
                .andExpect(status().isOk());
        orderA = place(soup, 1);
        orderB = place(lassi, 2);
        sessionBase = "/api/v1/hotels/" + hotelId + "/sessions/" + sessions.findAll().get(0).getId();
    }

    // ---------- helpers ----------

    private void member(String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String newDiner() throws Exception {
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-t1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String place(String itemId, int quantity) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":%d}]}".formatted(itemId, quantity))
                        .cookie(new Cookie("qr_customer", anita)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private int orderStatus(String itemId) throws Exception {
        return mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(itemId))
                        .cookie(new Cookie("qr_customer", anita)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus();
    }

    private int payStatus(String token, long amount) throws Exception {
        return mvc.perform(post(sessionBase + "/payments").header("Idempotency-Key", "race-pay-" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amountMinor\":%d}".formatted(amount)))
                .andReturn().getResponse().getStatus();
    }

    private int postStatus(String url, String token) throws Exception {
        return mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private int cancelStatus(String orderId) throws Exception {
        return mvc.perform(post("/api/v1/customer/orders/" + orderId + "/cancel")
                .cookie(new Cookie("qr_customer", anita))).andReturn().getResponse().getStatus();
    }

    private OrderStatus statusOf(String orderId) {
        return orders.findById(orderId).orElseThrow().getStatus();
    }

    private long paidOnBill() {
        return bills.findAll().stream().mapToLong(Bill::getPaidMinor).sum();
    }

    private int releases(String orderId) {
        return (int) orders.findById(orderId).orElseThrow().getStatusHistory().stream()
                .filter(c -> c.from() == OrderStatus.AWAITING_PAYMENT && c.to() == OrderStatus.PENDING).count();
    }

    private int exitsFromAwaiting(String orderId) {
        return (int) orders.findById(orderId).orElseThrow().getStatusHistory().stream()
                .filter(c -> c.from() == OrderStatus.AWAITING_PAYMENT).count();
    }

    /** Every step starts where the last one ended, and the last one ends at the order's current status. */
    private void assertHistoryIsAValidChain(String orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        List<OrderStatusChange> history = order.getStatusHistory();
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).from()).as("step %d of %s", i, orderId).isEqualTo(history.get(i - 1).to());
        }
        if (!history.isEmpty()) {
            assertThat(history.get(history.size() - 1).to()).isEqualTo(order.getStatus());
        }
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

    // ---------- case 5: an item sells out while orders are being submitted ----------

    @Test
    void anItemSoldOutWhileOrdersAreBeingSubmittedNeverLeavesAHalfOrder() throws Exception {
        long before = orders.count();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> orderStatus(soup));
        }
        tasks.add(() -> {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(soup)), new Update().set("available", false),
                    MenuItem.class);
            return 0;
        });

        List<Integer> statuses = runTogether(tasks);

        List<Integer> orderResults = statuses.subList(0, 10);
        assertThat(orderResults).as("an order is accepted or refused as sold out, never a 5xx")
                .allMatch(s -> s == 201 || s == 409);
        long accepted = orderResults.stream().filter(s -> s == 201).count();
        assertThat(orders.count() - before).as("every accepted order is stored, nothing else").isEqualTo(accepted);
        assertThat(orderStatus(soup)).as("once it is sold out nothing gets in").isEqualTo(409);
    }

    // ---------- pay-before-food: payments, manual releases ----------

    @Test
    void manyPaymentsAndManualReleasesAtOnceReleaseEachHeldOrderExactlyOnce() throws Exception {
        String cashier = login("cashier@example.com");
        String manager = login("manager@example.com");
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String token = i % 2 == 0 ? cashier : manager;
            tasks.add(() -> "pay:" + payStatus(token, 4500)); // 6 x 4500 = 27000, the whole bill
        }
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> "release:" + postStatus(sessionBase + "/release-paid-orders", cashier));
        }

        List<String> results = runTogether(tasks);

        assertThat(results.stream().filter(r -> r.startsWith("pay:")).toList()).containsOnly("pay:201");
        assertThat(results.stream().filter(r -> r.startsWith("release:")).toList()).containsOnly("release:200");
        assertThat(paidOnBill()).isEqualTo(27000);
        assertThat(payments.count()).isEqualTo(6);
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.PENDING);
        assertThat(releases(orderA)).isEqualTo(1);
        assertThat(releases(orderB)).isEqualTo(1);
        assertHistoryIsAValidChain(orderA);
        assertHistoryIsAValidChain(orderB);
        call(get("/api/v1/hotels/" + hotelId + "/kitchen/orders"), "cook@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    // ---------- pay-before-food: a payment races the guest cancelling the first held order ----------

    @RepeatedTest(6)
    void aPaymentRacingTheGuestsCancellationEndsInAConsistentState() throws Exception {
        String cashier = login("cashier@example.com");

        List<String> results = runTogether(List.of(
                () -> "pay:" + payStatus(cashier, 15000),
                () -> "cancel:" + cancelStatus(orderA)));

        int pay = Integer.parseInt(results.get(0).substring(4));
        int cancel = Integer.parseInt(results.get(1).substring(7));
        assertThat(pay).as("the payment is recorded, or refused because the total just dropped").isIn(201, 409);
        assertThat(cancel).isIn(200, 409);
        assertThat(paidOnBill()).isEqualTo(pay == 201 ? 15000L : 0L);

        OrderStatus a = statusOf(orderA);
        OrderStatus b = statusOf(orderB);
        if (a == OrderStatus.CANCELLED) {
            // 15000 paid covers the lassi order alone (12000), so it must have been released
            assertThat(b).isEqualTo(pay == 201 ? OrderStatus.PENDING : OrderStatus.AWAITING_PAYMENT);
        } else {
            assertThat(a).isEqualTo(OrderStatus.PENDING);
            assertThat(pay).isEqualTo(201);
            assertThat(b).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        }
        assertHistoryIsAValidChain(orderA);
        assertHistoryIsAValidChain(orderB);
    }

    // ---------- pay-before-food: a payment races the expiry of held orders ----------

    @RepeatedTest(5)
    void aPaymentRacingTheExpiryNeverReleasesAndCancelsTheSameOrder() throws Exception {
        mongo.updateMulti(new Query(), new Update().set("createdAt", Instant.now().minus(Duration.ofMinutes(40))),
                Order.class);
        String cashier = login("cashier@example.com");
        String manager = login("manager@example.com");

        List<String> results = runTogether(List.of(
                () -> "pay:" + payStatus(cashier, 15000),
                () -> "expire:" + postStatus("/api/v1/hotels/" + hotelId + "/payments/waiting-orders/expire", manager)));

        int pay = Integer.parseInt(results.get(0).substring(4));
        int expire = Integer.parseInt(results.get(1).substring(7));
        assertThat(expire).isEqualTo(200);
        assertThat(pay).as("recorded, or refused because the orders were already cancelled").isIn(201, 409);
        assertThat(paidOnBill()).isEqualTo(pay == 201 ? 15000L : 0L);
        for (String id : List.of(orderA, orderB)) {
            assertThat(exitsFromAwaiting(id)).as("an order leaves 'awaiting payment' at most once").isLessThanOrEqualTo(1);
            assertHistoryIsAValidChain(id);
        }
    }
}