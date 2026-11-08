package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.BillingSettingsRepository;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
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

class BillBreakdownIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String GST_5_SERVICE_10 =
            "{\"taxName\":\"GST\",\"taxRateBps\":500,\"taxInclusive\":false,\"serviceChargeBps\":1000}";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningSessionRepository sessions;
    @Autowired OrderRepository orders;
    @Autowired BillRepository bills;
    @Autowired BillingSettingsRepository settingsRepository;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String sessionId;
    String billUrl;
    String settingsUrl;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        sessions.deleteAll();
        orders.deleteAll();
        bills.deleteAll();
        settingsRepository.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "owner@example.com", "Owner Asha", HotelRole.OWNER);
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(otherHotelId, "otherwaiter@example.com", "Other Waiter", HotelRole.WAITER);

        sessionId = sessions.save(new DiningSession(hotelId, "t-1", "T1", Instant.now())).getId();
        // 2 x 25000 + 5 x 10000 = 100000, delivered
        Order delivered = orders.save(new Order(hotelId, sessionId, "t-1", "T1", "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("i1", "Paneer Tikka", Diet.VEG, null, null, List.of(), 25000L, 2, 50000L, null),
                        new OrderLine("i2", "Thali", Diet.VEG, null, null, List.of(), 10000L, 5, 50000L, null)),
                100000L, null, null, Instant.now()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(delivered.getId())),
                new Update().set("status", OrderStatus.DELIVERED), Order.class);

        billUrl = "/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/bill";
        settingsUrl = "/api/v1/hotels/" + hotelId + "/billing/settings";
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

    private ResultActions call(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private void configure(String json) throws Exception {
        call(put(settingsUrl), "owner@example.com", json).andExpect(status().isOk());
    }

    private ResultActions bill(String email) throws Exception {
        return call(get(billUrl), email, null);
    }

    private ResultActions discount(String email, String json) throws Exception {
        return call(post(billUrl + "/discounts"), email, json);
    }

    private String discountJson(String type, long value, String reason) {
        return "{\"type\":\"%s\",\"value\":%d,\"reason\":\"%s\"}".formatted(type, value, reason);
    }

    private void allowCashiersToAdjust(boolean on) {
        Hotel hotel = hotels.findById(hotelId).orElseThrow();
        hotel.setCashiersMayAdjustBills(on);
        hotels.save(hotel);
    }

    private void closeSession() {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("status", SessionStatus.CLOSED).set("active", false), DiningSession.class);
    }

    // ---------- settings ----------

    @Test
    void settingsStartAsNothingAndOnlyTheOwnerChangesThem() throws Exception {
        call(get(settingsUrl), "cashier@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxName").value("GST"))
                .andExpect(jsonPath("$.taxRateBps").value(0))
                .andExpect(jsonPath("$.taxInclusive").value(false))
                .andExpect(jsonPath("$.serviceChargeBps").value(0));
        call(get(settingsUrl), "manager@example.com", null).andExpect(status().isOk());
        call(get(settingsUrl), "owner@example.com", null).andExpect(status().isOk());
        call(get(settingsUrl), "w1@example.com", null).andExpect(status().isForbidden());
        call(get(settingsUrl), "cook@example.com", null).andExpect(status().isForbidden());
        call(get(settingsUrl), null, null).andExpect(status().isUnauthorized());
        call(get(settingsUrl), "otherwaiter@example.com", null).andExpect(status().isNotFound());

        for (String notOwner : List.of("manager@example.com", "cashier@example.com", "w1@example.com")) {
            call(put(settingsUrl), notOwner, GST_5_SERVICE_10).andExpect(status().isForbidden());
        }
        call(put(settingsUrl), "owner@example.com", GST_5_SERVICE_10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxRateBps").value(500))
                .andExpect(jsonPath("$.serviceChargeBps").value(1000))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        call(get(settingsUrl), "cashier@example.com", null).andExpect(jsonPath("$.taxRateBps").value(500));
    }

    @Test
    void invalidSettingsAreRefusedAndChangeNothing() throws Exception {
        configure(GST_5_SERVICE_10);

        for (String bad : List.of(
                "{\"taxName\":\"GST\",\"taxRateBps\":5001,\"taxInclusive\":false,\"serviceChargeBps\":0}",
                "{\"taxName\":\"GST\",\"taxRateBps\":-1,\"taxInclusive\":false,\"serviceChargeBps\":0}",
                "{\"taxName\":\"GST\",\"taxRateBps\":500,\"taxInclusive\":false,\"serviceChargeBps\":3001}",
                "{\"taxName\":\"  \",\"taxRateBps\":500,\"taxInclusive\":false,\"serviceChargeBps\":0}",
                "{\"taxName\":\"" + "x".repeat(21) + "\",\"taxRateBps\":500,\"taxInclusive\":false,\"serviceChargeBps\":0}",
                "{\"taxName\":\"GST\",\"taxRateBps\":500,\"serviceChargeBps\":0}",
                "{\"taxName\":\"GST\",\"taxInclusive\":false,\"serviceChargeBps\":0}")) {
            call(put(settingsUrl), "owner@example.com", bad)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        call(get(settingsUrl), "owner@example.com", null)
                .andExpect(jsonPath("$.taxRateBps").value(500)).andExpect(jsonPath("$.serviceChargeBps").value(1000));
        assertThat(settingsRepository.count()).isEqualTo(1);
    }

    // ---------- service charge and tax ----------

    @Test
    void serviceChargeAndTaxAreAddedOnTopOfTheSubtotal() throws Exception {
        configure(GST_5_SERVICE_10);

        bill("cashier@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(100000))
                .andExpect(jsonPath("$.discountMinor").value(0))
                .andExpect(jsonPath("$.serviceChargeMinor").value(10000))
                .andExpect(jsonPath("$.taxMinor").value(5500))
                .andExpect(jsonPath("$.totalMinor").value(115500))
                .andExpect(jsonPath("$.taxName").value("GST"))
                .andExpect(jsonPath("$.taxRateBps").value(500))
                .andExpect(jsonPath("$.taxInclusive").value(false))
                .andExpect(jsonPath("$.serviceChargeBps").value(1000));
    }

    @Test
    void inclusiveTaxIsShownButDoesNotRaiseTheTotal() throws Exception {
        configure("{\"taxName\":\"VAT\",\"taxRateBps\":500,\"taxInclusive\":true,\"serviceChargeBps\":0}");

        bill("cashier@example.com")
                .andExpect(jsonPath("$.taxName").value("VAT"))
                .andExpect(jsonPath("$.taxInclusive").value(true))
                .andExpect(jsonPath("$.taxMinor").value(4762))
                .andExpect(jsonPath("$.totalMinor").value(100000));
    }

    @Test
    void changedSettingsReachLiveBillsButNeverClearedOnes() throws Exception {
        bill("cashier@example.com").andExpect(jsonPath("$.totalMinor").value(100000)).andExpect(jsonPath("$.revision").value(1));

        configure(GST_5_SERVICE_10);
        bill("cashier@example.com").andExpect(jsonPath("$.totalMinor").value(115500)).andExpect(jsonPath("$.revision").value(2));
        bill("cashier@example.com").andExpect(jsonPath("$.revision").value(2)); // unchanged: no new revision

        closeSession();
        configure("{\"taxName\":\"GST\",\"taxRateBps\":1800,\"taxInclusive\":false,\"serviceChargeBps\":0}");

        bill("cashier@example.com")
                .andExpect(jsonPath("$.sessionStatus").value("CLOSED"))
                .andExpect(jsonPath("$.totalMinor").value(115500)) // frozen
                .andExpect(jsonPath("$.taxRateBps").value(500))
                .andExpect(jsonPath("$.revision").value(2));
    }

    // ---------- discounts ----------

    @Test
    void discountsComeOffBeforeServiceChargeAndTaxAndCanBeRemoved() throws Exception {
        configure(GST_5_SERVICE_10);

        MvcResult added = discount("manager@example.com", discountJson("PERCENT", 1000, "Regular guest"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountMinor").value(10000))
                .andExpect(jsonPath("$.serviceChargeMinor").value(9000))
                .andExpect(jsonPath("$.taxMinor").value(4950))
                .andExpect(jsonPath("$.totalMinor").value(103950))
                .andExpect(jsonPath("$.discounts.length()").value(1))
                .andExpect(jsonPath("$.discounts[0].type").value("PERCENT"))
                .andExpect(jsonPath("$.discounts[0].amountMinor").value(10000))
                .andExpect(jsonPath("$.discounts[0].reason").value("Regular guest"))
                .andExpect(jsonPath("$.discounts[0].byUserId").isNotEmpty())
                .andReturn();
        String first = JsonPath.read(added.getResponse().getContentAsString(), "$.discounts[0].id");

        discount("owner@example.com", discountJson("FLAT", 5000, "Cold food"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discounts.length()").value(2))
                .andExpect(jsonPath("$.discountMinor").value(15000));

        call(delete(billUrl + "/discounts/" + first), "manager@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discounts.length()").value(1))
                .andExpect(jsonPath("$.discounts[0].type").value("FLAT"))
                .andExpect(jsonPath("$.discountMinor").value(5000))
                .andExpect(jsonPath("$.totalMinor").value(109725)); // net 95000 + 9500 + 4975

        call(delete(billUrl + "/discounts/" + first), "manager@example.com", null).andExpect(status().isNotFound());
        call(delete(billUrl + "/discounts/no-such-id"), "manager@example.com", null).andExpect(status().isNotFound());
    }

    @Test
    void cashiersMayDiscountOnlyWhenTheOwnerAllowsIt() throws Exception {
        allowCashiersToAdjust(false);
        discount("cashier@example.com", discountJson("FLAT", 1000, "Birthday")).andExpect(status().isForbidden());

        allowCashiersToAdjust(true);
        discount("cashier@example.com", discountJson("FLAT", 1000, "Birthday")).andExpect(status().isCreated());

        for (String never : List.of("w1@example.com", "cook@example.com")) {
            discount(never, discountJson("FLAT", 1000, "Not allowed")).andExpect(status().isForbidden());
        }
        discount(null, discountJson("FLAT", 1000, "Anonymous")).andExpect(status().isUnauthorized());
        discount("otherwaiter@example.com", discountJson("FLAT", 1000, "Elsewhere")).andExpect(status().isNotFound());

        bill("cashier@example.com").andExpect(jsonPath("$.discounts.length()").value(1));
    }

    @Test
    void discountInputIsCheckedAndABillCarriesAtMostTen() throws Exception {
        for (String bad : List.of(
                "{\"value\":1000,\"reason\":\"No type\"}",
                discountJson("FLAT", 0, "Zero"),
                discountJson("FLAT", -5, "Negative"),
                discountJson("PERCENT", 10001, "Over one hundred percent"),
                discountJson("FLAT", 100001, "More than the bill"),
                discountJson("FLAT", 1000, "ab"),
                discountJson("FLAT", 1000, "   "),
                discountJson("FLAT", 1000, "x".repeat(201)))) {
            discount("manager@example.com", bad)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        bill("manager@example.com").andExpect(jsonPath("$.discounts.length()").value(0));

        for (int i = 0; i < 10; i++) {
            discount("manager@example.com", discountJson("FLAT", 100, "Bulk test " + i)).andExpect(status().isCreated());
        }
        discount("manager@example.com", discountJson("FLAT", 100, "One too many"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        bill("manager@example.com").andExpect(jsonPath("$.discounts.length()").value(10))
                .andExpect(jsonPath("$.discountMinor").value(1000));
    }

    @Test
    void aClearedTablesBillCannotBeDiscountedOrChanged() throws Exception {
        MvcResult added = discount("manager@example.com", discountJson("FLAT", 1000, "Before clearing"))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(added.getResponse().getContentAsString(), "$.discounts[0].id");
        closeSession();

        discount("manager@example.com", discountJson("FLAT", 500, "Too late")).andExpect(status().isConflict());
        call(delete(billUrl + "/discounts/" + id), "manager@example.com", null).andExpect(status().isConflict());

        bill("manager@example.com")
                .andExpect(jsonPath("$.discounts.length()").value(1))
                .andExpect(jsonPath("$.totalMinor").value(99000));
    }

    @Test
    void discountsOnUnknownOrForeignSessionsAreNotFound() throws Exception {
        discount("manager@example.com", discountJson("FLAT", 100, "Anything")).andExpect(status().isCreated());
        String foreign = sessions.save(new DiningSession(otherHotelId, "t-9", "T9", Instant.now())).getId();
        String base = "/api/v1/hotels/" + hotelId + "/sessions/";

        call(post(base + "no-such-session/bill/discounts"), "manager@example.com",
                discountJson("FLAT", 100, "Anything")).andExpect(status().isNotFound());
        call(post(base + foreign + "/bill/discounts"), "manager@example.com",
                discountJson("FLAT", 100, "Anything")).andExpect(status().isNotFound());
    }
}