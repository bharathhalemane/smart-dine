package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.HeldOrderSettingsRepository;
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
import java.util.Set;
import java.util.UUID;
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

class HeldOrderExpiryIT extends AbstractIntegrationTest {

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
    @Autowired HeldOrderSettingsRepository holdSettings;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String anita;
    String orderA;
    String orderB;
    String sessionBase;
    int keyCounter;

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
        holdSettings.deleteAll();
        keyCounter = 0;
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        member("owner@example.com", "Owner Om", HotelRole.OWNER);
        member("cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member("cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        String soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
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

    /** Pretends the order was placed this long ago. */
    private void age(String orderId, Duration howLong) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(orderId)),
                new Update().set("createdAt", Instant.now().minus(howLong)), Order.class);
    }

    private OrderStatus statusOf(String orderId) {
        return orders.findById(orderId).orElseThrow().getStatus();
    }

    private ResultActions expire(String email) throws Exception {
        return call(post("/api/v1/hotels/" + hotelId + "/payments/waiting-orders/expire"), email, null);
    }

    // ---------- expiry ----------

    @Test
    void anOrderOlderThanTheHoldTimeIsCancelledBySystemAndAFreshOneIsLeftAlone() throws Exception {
        age(orderA, Duration.ofMinutes(20)); // default hold is 15 minutes

        expire("cashier@example.com").andExpect(status().isOk()).andExpect(jsonPath("$.expired").value(1));

        Order expired = orders.findById(orderA).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(expired.getCancelledBy()).isEqualTo("SYSTEM");
        assertThat(expired.getCancelReason()).isEqualTo("Not paid within 15 minutes");
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        expire("cashier@example.com").andExpect(jsonPath("$.expired").value(0)); // nothing more to do
    }

    @Test
    void theOwnersHoldTimeDecidesWhenAnOrderExpires() throws Exception {
        age(orderA, Duration.ofMinutes(10));
        expire("cashier@example.com").andExpect(jsonPath("$.expired").value(0));

        call(put("/api/v1/hotels/" + hotelId + "/payments/hold-settings"), "owner@example.com",
                "{\"holdMinutes\":5}").andExpect(status().isOk()).andExpect(jsonPath("$.holdMinutes").value(5));

        expire("cashier@example.com").andExpect(jsonPath("$.expired").value(1));
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aTableThatHasPaidSomethingIsNeverExpiredAndIsMarkedPartlyPaid() throws Exception {
        call(post(sessionBase + "/payments").header("Idempotency-Key", "expiry-pay-key-%04d".formatted(++keyCounter)),
                "cashier@example.com", "{\"method\":\"CASH\",\"amountMinor\":5000}").andExpect(status().isCreated());
        age(orderA, Duration.ofMinutes(60));
        age(orderB, Duration.ofMinutes(60));

        expire("cashier@example.com").andExpect(jsonPath("$.expired").value(0));

        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        call(get("/api/v1/hotels/" + hotelId + "/payments/waiting-orders"), "cashier@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partlyPaid").value(true));
    }

    @Test
    void aPaidAndReleasedOrderIsNotTouched() throws Exception {
        call(post(sessionBase + "/payments").header("Idempotency-Key", "expiry-pay-key-%04d".formatted(++keyCounter)),
                "cashier@example.com", "{\"method\":\"CASH\",\"amountMinor\":15000}").andExpect(status().isCreated());
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING);
        age(orderA, Duration.ofMinutes(60));

        expire("cashier@example.com").andExpect(jsonPath("$.expired").value(0));
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING);
    }

    // ---------- the list and the settings ----------

    @Test
    void theCashiersListShowsHeldOrdersOldestFirstWithAnExpiryTime() throws Exception {
        call(get("/api/v1/hotels/" + hotelId + "/payments/waiting-orders"), "cashier@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderId").value(orderA))
                .andExpect(jsonPath("$[0].tableNumber").value("T1"))
                .andExpect(jsonPath("$[0].subtotalMinor").value(15000))
                .andExpect(jsonPath("$[0].partlyPaid").value(false))
                .andExpect(jsonPath("$[0].expiresAt").exists())
                .andExpect(jsonPath("$[1].orderId").value(orderB));
    }

    @Test
    void onlyThePermittedCanUseTheListExpiryAndSettings() throws Exception {
        call(get("/api/v1/hotels/" + hotelId + "/payments/waiting-orders"), "cook@example.com", null)
                .andExpect(status().isForbidden());
        expire("cook@example.com").andExpect(status().isForbidden());
        expire(null).andExpect(status().isUnauthorized());
        call(put("/api/v1/hotels/" + hotelId + "/payments/hold-settings"), "cashier@example.com",
                "{\"holdMinutes\":30}").andExpect(status().isForbidden());
    }

    @Test
    void theHoldTimeDefaultsToFifteenAndMustBeBetweenFiveAndOneEighty() throws Exception {
        call(get("/api/v1/hotels/" + hotelId + "/payments/hold-settings"), "cashier@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.holdMinutes").value(15));

        call(put("/api/v1/hotels/" + hotelId + "/payments/hold-settings"), "owner@example.com",
                "{\"holdMinutes\":4}").andExpect(status().isBadRequest());
        call(put("/api/v1/hotels/" + hotelId + "/payments/hold-settings"), "owner@example.com",
                "{\"holdMinutes\":181}").andExpect(status().isBadRequest());
        call(put("/api/v1/hotels/" + hotelId + "/payments/hold-settings"), "owner@example.com",
                "{}").andExpect(status().isBadRequest());
    }
}