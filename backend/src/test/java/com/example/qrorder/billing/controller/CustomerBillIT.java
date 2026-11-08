package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
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
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class CustomerBillIT extends AbstractIntegrationTest {

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
    @Autowired BillRepository bills;

    String soup;
    String lassi;
    String anita; // first diner, "Guest 1"
    String ravi;  // second diner at the same table, "Guest 2"

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
        bills.deleteAll();
        Instant now = Instant.now();

        String hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now))
                .getId();
        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();

        anita = newDiner();
        ravi = newDiner();
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

    private ResultActions bill(String cookie) throws Exception {
        var request = get("/api/v1/customer/bill");
        if (cookie != null) {
            request.cookie(new Cookie("qr_customer", cookie));
        }
        return mvc.perform(request);
    }

    // ---------- tests ----------

    @Test
    void everyDinerAtTheTableSeesTheSameBillOnceFoodIsDelivered() throws Exception {
        place(anita, soup, 1);   // 15000
        place(ravi, lassi, 2);   // 12000

        bill(anita)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.subtotalMinor").value(0))
                .andExpect(jsonPath("$.ordersInProgress").value(2))
                .andExpect(jsonPath("$.complete").value(false));

        mongo.updateMulti(new Query(), new Update().set("status", OrderStatus.DELIVERED), Order.class);

        String body = bill(anita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableNumber").value("T1"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.subtotalMinor").value(27000))
                .andExpect(jsonPath("$.totalMinor").value(27000))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.guests[0].label").value("Guest 1"))
                .andExpect(jsonPath("$.guests[0].subtotalMinor").value(15000))
                .andExpect(jsonPath("$.guests[1].label").value("Guest 2"))
                .andExpect(jsonPath("$.guests[1].subtotalMinor").value(12000))
                .andReturn().getResponse().getContentAsString();

        bill(ravi)
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(body, "$.id")))
                .andExpect(jsonPath("$.subtotalMinor").value(27000));
        assertThat(bills.count()).isEqualTo(1);
    }

    @Test
    void aDinerNeedsAValidCookie() throws Exception {
        bill(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        bill("not-a-real-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void onceTheTableIsClearedTheDinerCannotReadTheBillAnymore() throws Exception {
        place(anita, soup, 1);
        mongo.updateMulti(new Query(Criteria.where("hotelId").exists(true)),
                new Update().set("status", SessionStatus.CLOSED).set("active", false), DiningSession.class);

        bill(anita).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
    }
}