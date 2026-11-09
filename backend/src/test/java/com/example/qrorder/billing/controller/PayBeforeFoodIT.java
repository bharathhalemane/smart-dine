package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.repository.BillRepository;
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
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
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

class PayBeforeFoodIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

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
    @Autowired BillRepository bills;
    @Autowired PaymentSettingsRepository paymentSettings;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String tableId;
    String soup;
    String lassi;
    String anita;
    String settingsUrl;

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
        paymentSettings.deleteAll();
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        member("owner@example.com", "Owner Om", HotelRole.OWNER);
        member("manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member("cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member("cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);

        tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();

        anita = newDiner();
        settingsUrl = "/api/v1/hotels/" + hotelId + "/payments/settings";
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

    private ResultActions setTiming(String email, String timing) throws Exception {
        String body = timing == null
                ? "{\"enabledMethods\":[\"CASH\"],\"partialPaymentsAllowed\":true}"
                : "{\"enabledMethods\":[\"CASH\"],\"partialPaymentsAllowed\":true,\"paymentTiming\":\"%s\"}".formatted(timing);
        return call(put(settingsUrl), email, body);
    }

    private String place(String itemId, int quantity) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":%d}]}".formatted(itemId, quantity))
                        .cookie(new Cookie("qr_customer", anita)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String sessionId() {
        return sessions.findAll().get(0).getId();
    }

    private ResultActions staffBill(String email) throws Exception {
        return call(get("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId() + "/bill"), email, null);
    }

    // ---------- the setting ----------

    @Test
    void theDefaultIsToPayAfterTheMealAndOrdersGoStraightToTheKitchen() throws Exception {
        call(get(settingsUrl), "owner@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentTiming").value("AFTER_MEAL"));

        String orderId = place(soup, 1);

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        call(get("/api/v1/hotels/" + hotelId + "/kitchen/orders"), "cook@example.com", null)
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void onlyTheOwnerChangesWhenGuestsPayAndLeavingItOutKeepsIt() throws Exception {
        setTiming("manager@example.com", "BEFORE_FOOD").andExpect(status().isForbidden());
        setTiming("cashier@example.com", "BEFORE_FOOD").andExpect(status().isForbidden());
        setTiming(null, "BEFORE_FOOD").andExpect(status().isUnauthorized());
        setTiming("owner@example.com", "SOMETIME").andExpect(status().isBadRequest());

        setTiming("owner@example.com", "BEFORE_FOOD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentTiming").value("BEFORE_FOOD"));
        setTiming("owner@example.com", null) // saving other settings without mentioning timing
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentTiming").value("BEFORE_FOOD"));
        call(get(settingsUrl), "cashier@example.com", null).andExpect(jsonPath("$.paymentTiming").value("BEFORE_FOOD"));
    }

    // ---------- held orders ----------

    @Test
    void whenGuestsPayFirstANewOrderWaitsForPaymentAndTheKitchenDoesNotSeeIt() throws Exception {
        setTiming("owner@example.com", "BEFORE_FOOD").andExpect(status().isOk());

        String orderId = place(soup, 1);

        mvc.perform(get("/api/v1/customer/orders/" + orderId).cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
        call(get("/api/v1/hotels/" + hotelId + "/kitchen/orders"), "cook@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call(post("/api/v1/hotels/" + hotelId + "/kitchen/orders/" + orderId + "/accept"), "cook@example.com", null)
                .andExpect(status().isConflict());
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @Test
    void theBillCountsOrdersThatAreWaitingForPayment() throws Exception {
        setTiming("owner@example.com", "BEFORE_FOOD").andExpect(status().isOk());
        place(soup, 1);   // 15000
        place(lassi, 2);  // 12000

        staffBill("cashier@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(27000))
                .andExpect(jsonPath("$.totalMinor").value(27000))
                .andExpect(jsonPath("$.balanceMinor").value(27000))
                .andExpect(jsonPath("$.ordersBilled").value(2))
                .andExpect(jsonPath("$.ordersInProgress").value(0))
                .andExpect(jsonPath("$.complete").value(true));
    }

    @Test
    void inAnAfterMealHotelTheBillStillCountsOnlyDeliveredOrders() throws Exception {
        place(soup, 1);

        staffBill("cashier@example.com")
                .andExpect(jsonPath("$.subtotalMinor").value(0))
                .andExpect(jsonPath("$.ordersInProgress").value(1))
                .andExpect(jsonPath("$.complete").value(false));
    }

    @Test
    void aDinerOrStaffCanCancelAnOrderThatIsWaitingForPayment() throws Exception {
        setTiming("owner@example.com", "BEFORE_FOOD").andExpect(status().isOk());
        String mine = place(soup, 1);
        String other = place(lassi, 2);

        mvc.perform(post("/api/v1/customer/orders/" + mine + "/cancel").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/customer/orders/" + mine + "/cancel").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk()); // repeating it is harmless

        call(post("/api/v1/hotels/" + hotelId + "/kitchen/orders/" + other + "/cancel"), "manager@example.com",
                "{\"reason\":\"Guest left\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        staffBill("cashier@example.com")
                .andExpect(jsonPath("$.subtotalMinor").value(0)).andExpect(jsonPath("$.ordersBilled").value(0));
    }

    @Test
    void theTimingCannotBeChangedWhileOrdersAreWaitingForPayment() throws Exception {
        setTiming("owner@example.com", "BEFORE_FOOD").andExpect(status().isOk());
        String orderId = place(soup, 1);

        setTiming("owner@example.com", "AFTER_MEAL")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Some orders are still waiting for payment. Settle or cancel them before changing when guests pay"));
        setTiming("owner@example.com", "BEFORE_FOOD").andExpect(status().isOk()); // not a change: allowed

        mvc.perform(post("/api/v1/customer/orders/" + orderId + "/cancel").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk());
        setTiming("owner@example.com", "AFTER_MEAL")
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentTiming").value("AFTER_MEAL"));
    }

    // ---------- around the edges ----------

    @Test
    void aTableWithOrdersWaitingForPaymentCannotBeClearedByAccident() throws Exception {
        setTiming("owner@example.com", "BEFORE_FOOD").andExpect(status().isOk());
        place(soup, 1);
        place(lassi, 2);

        call(post("/api/v1/hotels/" + hotelId + "/waiter/tables/" + tableId + "/clear"), "manager@example.com", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This table still has 2 orders in progress"));
    }

    @Test
    void theDinerIsToldWhenPaymentComesFirst() throws Exception {
        mvc.perform(get("/api/v1/customer/payment-options").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentTiming").value("AFTER_MEAL"));

        setTiming("owner@example.com", "BEFORE_FOOD").andExpect(status().isOk());
        place(soup, 1);

        mvc.perform(get("/api/v1/customer/payment-options").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentTiming").value("BEFORE_FOOD"))
                .andExpect(jsonPath("$.amountDueMinor").value(15000));
    }
}