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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

class OrderReleaseIT extends AbstractIntegrationTest {

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
    String anita;
    String orderA; // Soup, 15000, the older order
    String orderB; // Lassi x2, 12000
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
        keyCounter = 0;
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        member("owner@example.com", "Owner Om", HotelRole.OWNER);
        member("manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member("cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member("cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        String soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        String lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();

        anita = newDiner();
        setTiming("BEFORE_FOOD").andExpect(status().isOk());
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

    private ResultActions setTiming(String timing) throws Exception {
        return call(put("/api/v1/hotels/" + hotelId + "/payments/settings"), "owner@example.com",
                "{\"enabledMethods\":[\"CASH\"],\"partialPaymentsAllowed\":true,\"paymentTiming\":\"%s\"}".formatted(timing));
    }

    private String place(String itemId, int quantity) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":%d}]}".formatted(itemId, quantity))
                        .cookie(new Cookie("qr_customer", anita)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void pay(long amount) throws Exception {
        call(post(sessionBase + "/payments").header("Idempotency-Key", "release-pay-key-%04d".formatted(++keyCounter)),
                "cashier@example.com", "{\"method\":\"CASH\",\"amountMinor\":%d}".formatted(amount))
                .andExpect(status().isCreated());
    }

    private OrderStatus statusOf(String orderId) {
        return orders.findById(orderId).orElseThrow().getStatus();
    }

    private void kitchenSees(int count) throws Exception {
        call(get("/api/v1/hotels/" + hotelId + "/kitchen/orders"), "cook@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(count));
    }

    // ---------- release by payment ----------

    @Test
    void payingTheFirstOrdersAmountReleasesItAndLeavesTheOtherHeld() throws Exception {
        pay(15000);

        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        kitchenSees(1);
    }

    @Test
    void aPartPaymentThatCoversNothingReleasesNothing() throws Exception {
        pay(14999);

        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        kitchenSees(0);
    }

    @Test
    void payingTheWholeBillReleasesEverythingOldestFirstAndRecordsWhoDidIt() throws Exception {
        pay(27000);

        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.PENDING);
        kitchenSees(2);
        List<OrderStatusChange> history = orders.findById(orderA).orElseThrow().getStatusHistory();
        OrderStatusChange last = history.get(history.size() - 1);
        assertThat(last.from()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(last.to()).isEqualTo(OrderStatus.PENDING);
        assertThat(last.actorType()).isEqualTo("SYSTEM");
    }

    @Test
    void theSecondOrdersMoneyCannotJumpTheQueue() throws Exception {
        pay(12000); // exactly the lassi order, but the soup order is older
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        pay(3000); // 15000 paid: the soup order is covered
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        pay(12000); // 27000 paid
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.PENDING);
    }

    // ---------- release by other events ----------

    @Test
    void cancellingTheFirstHeldOrderFreesThePaymentForTheNext() throws Exception {
        pay(12000);
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        mvc.perform(post("/api/v1/customer/orders/" + orderA + "/cancel").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk());

        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.PENDING);
        kitchenSees(1);
    }

    @Test
    void aDiscountCanMakeAnEarlierPaymentEnough() throws Exception {
        pay(13000);
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        call(post(sessionBase + "/bill/discounts"), "manager@example.com",
                "{\"type\":\"FLAT\",\"value\":2000,\"reason\":\"Welcome offer\"}").andExpect(status().isCreated());

        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING); // 15000 - 2000 = 13000, which is paid
        assertThat(statusOf(orderB)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    // ---------- by hand, and untouched hotels ----------

    @Test
    void aCashierCanAskForAnotherLookAndOthersCannot() throws Exception {
        call(get(sessionBase + "/bill"), "cashier@example.com", null).andExpect(status().isOk()); // makes the bill exist
        mongo.updateFirst(new Query(), new Update().set("paidMinor", 15000L), Bill.class); // paid without a release

        call(post(sessionBase + "/release-paid-orders"), "cook@example.com", null).andExpect(status().isForbidden());
        call(post(sessionBase + "/release-paid-orders"), null, null).andExpect(status().isUnauthorized());
        call(post("/api/v1/hotels/" + hotelId + "/sessions/no-such-session/release-paid-orders"),
                "cashier@example.com", null).andExpect(status().isNotFound());
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        call(post(sessionBase + "/release-paid-orders"), "cashier@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.released").value(1));
        assertThat(statusOf(orderA)).isEqualTo(OrderStatus.PENDING);

        call(post(sessionBase + "/release-paid-orders"), "cashier@example.com", null)
                .andExpect(jsonPath("$.released").value(0)); // repeating it does nothing more
    }

    @Test
    void aHotelThatPaysAfterTheMealIsUntouched() throws Exception {
        mvc.perform(post("/api/v1/customer/orders/" + orderA + "/cancel").cookie(new Cookie("qr_customer", anita)));
        mvc.perform(post("/api/v1/customer/orders/" + orderB + "/cancel").cookie(new Cookie("qr_customer", anita)));
        setTiming("AFTER_MEAL").andExpect(status().isOk());

        call(post(sessionBase + "/release-paid-orders"), "cashier@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.released").value(0));
    }
}