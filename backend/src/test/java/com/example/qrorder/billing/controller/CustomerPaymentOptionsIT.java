package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.common.security.Tokens;
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
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class CustomerPaymentOptionsIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");
    private static final String URL = "/api/v1/customer/payment-options";

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
    @Autowired PaymentSettingsRepository paymentSettings;

    String hotelId;
    String anita;

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
        paymentSettings.deleteAll();
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        String soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        String lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();

        anita = newDiner();
        place(anita, soup, 1);  // 15000
        place(anita, lassi, 2); // 12000 -> 27000 once delivered
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

    private void deliverEverything() {
        mongo.updateMulti(new Query(), new Update().set("status", OrderStatus.DELIVERED), Order.class);
    }

    private void useUpi() {
        paymentSettings.save(new PaymentSettings(hotelId, List.of(PaymentMethod.CASH, PaymentMethod.UPI),
                "asha@okbank", "Spice Garden", true, "u", Instant.now()));
    }

    private ResultActions options(String cookie) throws Exception {
        var request = get(URL);
        if (cookie != null) {
            request.cookie(new Cookie("qr_customer", cookie));
        }
        return mvc.perform(request);
    }

    // ---------- tests ----------

    @Test
    void aDinerGetsTheMethodsAndAReadyToOpenUpiLinkForTheBill() throws Exception {
        useUpi();
        deliverEverything();

        options(anita)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.methods.length()").value(2))
                .andExpect(jsonPath("$.methods[0]").value("CASH"))
                .andExpect(jsonPath("$.methods[1]").value("UPI"))
                .andExpect(jsonPath("$.upiId").value("asha@okbank"))
                .andExpect(jsonPath("$.payeeName").value("Spice Garden"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.amountDueMinor").value(27000))
                .andExpect(jsonPath("$.billComplete").value(true))
                .andExpect(jsonPath("$.partialPaymentsAllowed").value(true))
                .andExpect(jsonPath("$.upiLink")
                        .value("upi://pay?pa=asha@okbank&pn=Spice%20Garden&am=270.00&cu=INR&tn=Table%20T1"));
    }

    @Test
    void whileFoodIsStillComingTheLinkCarriesNoAmountAndTheBillIsMarkedIncomplete() throws Exception {
        useUpi();

        options(anita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountDueMinor").value(0))
                .andExpect(jsonPath("$.billComplete").value(false))
                .andExpect(jsonPath("$.upiLink")
                        .value("upi://pay?pa=asha@okbank&pn=Spice%20Garden&cu=INR&tn=Table%20T1"));
    }

    @Test
    void withoutUpiThereIsNoUpiIdOrLinkAndTheDefaultIsCashOnly() throws Exception {
        deliverEverything();

        options(anita) // the hotel never configured anything
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methods.length()").value(1))
                .andExpect(jsonPath("$.methods[0]").value("CASH"))
                .andExpect(jsonPath("$.upiId").doesNotExist())
                .andExpect(jsonPath("$.payeeName").doesNotExist())
                .andExpect(jsonPath("$.upiLink").doesNotExist())
                .andExpect(jsonPath("$.amountDueMinor").value(27000));

        // UPI details stored but UPI switched off: still nothing is shown
        paymentSettings.save(new PaymentSettings(hotelId, List.of(PaymentMethod.CARD), "asha@okbank",
                "Spice Garden", false, "u", Instant.now()));
        options(anita)
                .andExpect(jsonPath("$.methods[0]").value("CARD"))
                .andExpect(jsonPath("$.upiId").doesNotExist())
                .andExpect(jsonPath("$.upiLink").doesNotExist())
                .andExpect(jsonPath("$.partialPaymentsAllowed").value(false));
    }

    @Test
    void aDinerNeedsAValidCookie() throws Exception {
        options(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        options("not-a-real-token").andExpect(status().isUnauthorized());
    }

    @Test
    void afterAPartPaymentTheLinkAsksForTheBalanceOnly() throws Exception {
        useUpi();
        deliverEverything();
        options(anita).andExpect(status().isOk()); // makes the bill exist

        mongo.updateFirst(new Query(), new Update().set("paidMinor", 10000L), Bill.class);

        options(anita)
                .andExpect(jsonPath("$.amountDueMinor").value(17000))
                .andExpect(jsonPath("$.upiLink")
                        .value("upi://pay?pa=asha@okbank&pn=Spice%20Garden&am=170.00&cu=INR&tn=Table%20T1"));
    }
}