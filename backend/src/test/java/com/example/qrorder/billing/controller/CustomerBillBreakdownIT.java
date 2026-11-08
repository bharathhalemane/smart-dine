package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.BillingSettings;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.BillingSettingsRepository;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class CustomerBillBreakdownIT extends AbstractIntegrationTest {

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
    @Autowired BillRepository bills;
    @Autowired BillingSettingsRepository settings;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String anita;

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
        settings.deleteAll();
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        User manager = new User("manager@example.com", encoder.encode(PASSWORD), "Manager Meera");
        manager.setEmailVerified(true);
        manager = users.save(manager);
        memberships.save(new HotelMembership(hotelId, manager.getId(), Set.of(HotelRole.MANAGER), manager.getId(), now));

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        String soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        String lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();
        settings.save(new BillingSettings(hotelId, "GST", 500, false, 1000, "u", now));

        anita = newDiner();
        place(anita, soup, 1);  // 15000
        place(anita, lassi, 2); // 12000  -> subtotal 27000
        mongo.updateMulti(new Query(), new Update().set("status", OrderStatus.DELIVERED), Order.class);
    }

    // ---------- helpers ----------

    private String newDiner() throws Exception {
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-t1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void place(String cookie, String itemId, int quantity) throws Exception {
        mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":%d}]}".formatted(itemId, quantity))
                        .cookie(new Cookie("qr_customer", cookie)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
    }

    private String managerToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    // ---------- test ----------

    @Test
    void aDinerSeesTheFullBreakdownButNotWhoGaveTheDiscountOrWhy() throws Exception {
        mvc.perform(get("/api/v1/customer/bill").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(27000))
                .andExpect(jsonPath("$.serviceChargeMinor").value(2700))
                .andExpect(jsonPath("$.taxMinor").value(1485))
                .andExpect(jsonPath("$.totalMinor").value(31185));

        String sessionId = sessions.findAll().get(0).getId();
        mvc.perform(post("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/bill/discounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FLAT\",\"value\":2000,\"reason\":\"Staff comp, guest unhappy\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/customer/bill").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountMinor").value(2000))
                .andExpect(jsonPath("$.serviceChargeMinor").value(2500))
                .andExpect(jsonPath("$.taxMinor").value(1375))
                .andExpect(jsonPath("$.totalMinor").value(28875))
                .andExpect(jsonPath("$.taxName").value("GST"))
                .andExpect(jsonPath("$.discounts.length()").value(1))
                .andExpect(jsonPath("$.discounts[0].amountMinor").value(2000))
                .andExpect(jsonPath("$.discounts[0].reason").doesNotExist())
                .andExpect(jsonPath("$.discounts[0].byUserId").doesNotExist())
                .andExpect(jsonPath("$.discounts[0].id").doesNotExist());
    }
}