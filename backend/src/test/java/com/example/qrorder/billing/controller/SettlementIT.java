package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.model.SettlementSettings;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.billing.repository.SettlementSettingsRepository;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.WaiterEventRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

class SettlementIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired DiningSessionRepository sessions;
    @Autowired OrderRepository orders;
    @Autowired BillRepository bills;
    @Autowired PaymentRepository payments;
    @Autowired PaymentSettingsRepository paymentSettings;
    @Autowired SettlementSettingsRepository settlement;
    @Autowired WaiterEventRepository waiterEvents;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String tableId;
    String sessionId;
    String orderId;
    int keyCounter;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        sessions.deleteAll();
        orders.deleteAll();
        bills.deleteAll();
        payments.deleteAll();
        paymentSettings.deleteAll();
        settlement.deleteAll();
        waiterEvents.deleteAll();
        keyCounter = 0;
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member("owner@example.com", "Owner Om", HotelRole.OWNER);
        member("manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member("cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        paymentSettings.save(new PaymentSettings(hotelId, List.of(PaymentMethod.CASH), null, null, true, "u",
                Instant.now()));

        tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, Instant.now())).getId();
        sessionId = sessions.save(new DiningSession(hotelId, tableId, "T1", Instant.now())).getId();
        orderId = orders.save(new Order(hotelId, sessionId, tableId, "T1", "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("i1", "Thali", Diet.VEG, null, null, List.of(), 50000L, 2, 100000L, null)),
                100000L, null, null, Instant.now())).getId();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)),
                new Update().set("status", OrderStatus.DELIVERED), Order.class);
    }

    // ---------- helpers ----------

    private void member(String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private void require(boolean on) {
        settlement.deleteAll();
        settlement.save(new SettlementSettings(hotelId, on, "u", Instant.now()));
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

    private ResultActions pay(long amount) throws Exception {
        return call(post("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/payments")
                        .header("Idempotency-Key", "settlement-key-%04d".formatted(++keyCounter)), "cashier@example.com",
                "{\"method\":\"CASH\",\"amountMinor\":%d}".formatted(amount));
    }

    private ResultActions clear(String email, boolean force) throws Exception {
        return call(post("/api/v1/hotels/" + hotelId + "/waiter/tables/" + tableId + "/clear?force=" + force),
                email, null);
    }

    private boolean sessionIsActive() {
        return sessions.findById(sessionId).orElseThrow().isActive();
    }

    private String auditTrail() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper.writeValueAsString(waiterEvents.findAll());
    }

    // ---------- the settings ----------

    @Test
    void settlementIsOffUntilTheOwnerTurnsItOn() throws Exception {
        String url = "/api/v1/hotels/" + hotelId + "/settlement/settings";

        call(get(url), "manager@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.requireSettlementBeforeClear").value(false));
        call(put(url), "manager@example.com", "{\"requireSettlementBeforeClear\":true}")
                .andExpect(status().isForbidden());
        call(put(url), "cashier@example.com", "{\"requireSettlementBeforeClear\":true}")
                .andExpect(status().isForbidden());
        call(put(url), "owner@example.com", "{}").andExpect(status().isBadRequest());
        call(put(url), "owner@example.com", "{\"requireSettlementBeforeClear\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.requireSettlementBeforeClear").value(true));
        call(get(url), "manager@example.com", null)
                .andExpect(jsonPath("$.requireSettlementBeforeClear").value(true));
        call(put(url), "owner@example.com", "{\"requireSettlementBeforeClear\":false}").andExpect(status().isOk());
        assertThat(settlement.count()).isEqualTo(1);
        call(get(url), "cashier@example.com", null).andExpect(status().isForbidden());
    }

    // ---------- clearing ----------

    @Test
    void withTheSwitchOffAnUnpaidTableStillClears() throws Exception {
        clear("manager@example.com", false).andExpect(status().isOk());
        assertThat(sessionIsActive()).isFalse();
    }

    @Test
    void anUnpaidTableCannotBeClearedWhenSettlementIsRequired() throws Exception {
        require(true);

        clear("manager@example.com", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This table still has 1000.00 INR unpaid"));
        assertThat(sessionIsActive()).isTrue();

        pay(40000).andExpect(status().isCreated());
        clear("manager@example.com", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This table still has 600.00 INR unpaid"));
        assertThat(sessionIsActive()).isTrue();
    }

    @Test
    void aFullyPaidTableClearsAndItsBillIsFrozen() throws Exception {
        require(true);
        pay(100000).andExpect(status().isCreated());

        clear("manager@example.com", false).andExpect(status().isOk());

        assertThat(sessionIsActive()).isFalse();
        assertThat(bills.findByDiningSessionId(sessionId).orElseThrow().getPaidMinor()).isEqualTo(100000);
        // the order changing afterwards no longer moves the closed bill
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)),
                new Update().set("status", OrderStatus.CANCELLED), Order.class);
        call(get("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/bill"), "manager@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(100000));
    }

    @Test
    void aManagerCanForceAnUnpaidClearAndTheAmountIsAudited() throws Exception {
        require(true);
        pay(40000).andExpect(status().isCreated());

        clear("manager@example.com", true).andExpect(status().isOk());

        assertThat(sessionIsActive()).isFalse();
        assertThat(auditTrail()).contains("Cleared with 600.00 INR unpaid");
    }

    @Test
    void anOverpaidTableBlocksClearingUntilForced() throws Exception {
        require(true);
        pay(100000).andExpect(status().isCreated());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)),
                new Update().set("status", OrderStatus.CANCELLED), Order.class); // total drops to 0

        clear("manager@example.com", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This table has been overpaid by 1000.00 INR, settle the refund first"));

        clear("manager@example.com", true).andExpect(status().isOk());
        assertThat(auditTrail()).contains("Cleared with 1000.00 INR overpaid");
    }

    @Test
    void aSettledTableForcedClosedLeavesNoUnpaidNote() throws Exception {
        require(true);
        pay(100000).andExpect(status().isCreated());

        clear("manager@example.com", true).andExpect(status().isOk());

        assertThat(auditTrail()).doesNotContain("unpaid");
    }
}