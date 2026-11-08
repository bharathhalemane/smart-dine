package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderOption;
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
import java.time.temporal.ChronoUnit;
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

class BillIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningSessionRepository sessions;
    @Autowired OrderRepository orders;
    @Autowired BillRepository bills;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String s1;        // the busy table
    String pending;   // an order of s1 that is still in progress

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        sessions.deleteAll();
        orders.deleteAll();
        bills.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(otherHotelId, "otherwaiter@example.com", "Other Waiter", HotelRole.WAITER);

        s1 = session(hotelId, "t-1", "T1");

        // Guest 1: 2 x Paneer Tikka (56000) + 3 x Naan (12000) = 68000, delivered
        order(s1, "Guest 1", OrderStatus.DELIVERED, 40,
                line("Paneer Tikka", null, List.of(), 28000L, 2, 56000L), line("Naan", null, List.of(), 4000L, 3, 12000L));
        // Guest 2: Biryani with an add-on (34000) delivered; the dessert could not be served so it is not billed
        order(s1, "Guest 2", OrderStatus.DELIVERED, 30,
                line("Biryani", "Large", List.of(new OrderOption("g1", "Extras", "o1", "Extra raita", 2000L)), 34000L,
                        1, 34000L),
                new OrderLine("item-9", "Dessert", Diet.VEG, null, null, List.of(), 15000L, 1, 15000L, null)
                        .markUnavailable("Ran out"));
        order(s1, "Guest 1", OrderStatus.CANCELLED, 20, line("Thali", null, List.of(), 99000L, 1, 99000L));
        pending = order(s1, "Guest 2", OrderStatus.PENDING, 10, line("Lassi", null, List.of(), 6000L, 2, 12000L));
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String session(String hotel, String tableId, String number) {
        return sessions.save(new DiningSession(hotel, tableId, number, Instant.now())).getId();
    }

    private OrderLine line(String name, String variant, List<OrderOption> options, long unit, int quantity,
                           long total) {
        return new OrderLine("item-" + name, name, Diet.VEG, null, variant, options, unit, quantity, total, null);
    }

    private String order(String sessionId, String guest, OrderStatus status, long minutesAgo, OrderLine... lines) {
        long subtotal = 0;
        for (OrderLine l : lines) {
            if (l.stillOrdered()) {
                subtotal += l.lineTotalMinor();
            }
        }
        Order saved = orders.save(new Order(hotelId, sessionId, "t-1", "T1", "cust-" + guest, guest, "INR",
                List.of(lines), subtotal, null, null, Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)));
        setStatus(saved.getId(), status);
        return saved.getId();
    }

    private void setStatus(String orderId, OrderStatus status) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)),
                new Update().set("status", status), Order.class);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions bill(String email, String sessionId) throws Exception {
        var request = get("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/bill");
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        return mvc.perform(request);
    }

    private void closeSession(String sessionId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("status", SessionStatus.CLOSED).set("active", false), DiningSession.class);
    }

    // ---------- tests ----------

    @Test
    void theBillAddsUpTheLinesOfDeliveredOrdersOnly() throws Exception {
        bill("cashier@example.com", s1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(s1))
                .andExpect(jsonPath("$.sessionStatus").value("OPEN"))
                .andExpect(jsonPath("$.tableNumber").value("T1"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].itemName").value("Paneer Tikka"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].lineTotalMinor").value(56000))
                .andExpect(jsonPath("$.lines[0].customerLabel").value("Guest 1"))
                .andExpect(jsonPath("$.lines[2].itemName").value("Biryani"))
                .andExpect(jsonPath("$.lines[2].variantName").value("Large"))
                .andExpect(jsonPath("$.lines[2].options[0]").value("Extra raita"))
                .andExpect(jsonPath("$.lines[2].customerLabel").value("Guest 2"))
                .andExpect(jsonPath("$.subtotalMinor").value(102000))
                .andExpect(jsonPath("$.totalMinor").value(102000))
                .andExpect(jsonPath("$.ordersBilled").value(2))
                .andExpect(jsonPath("$.ordersInProgress").value(1))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.guests.length()").value(2))
                .andExpect(jsonPath("$.guests[0].label").value("Guest 1"))
                .andExpect(jsonPath("$.guests[0].subtotalMinor").value(68000))
                .andExpect(jsonPath("$.guests[1].label").value("Guest 2"))
                .andExpect(jsonPath("$.guests[1].subtotalMinor").value(34000))
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void theBillGrowsWhenAnOrderIsDeliveredAndOnlyChangesItsRevisionWhenSomethingChanged() throws Exception {
        MvcResult first = bill("cashier@example.com", s1).andExpect(status().isOk()).andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        bill("w1@example.com", s1).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.revision").value(1));
        bill("manager@example.com", s1).andExpect(jsonPath("$.revision").value(1)); // reading changes nothing

        setStatus(pending, OrderStatus.DELIVERED);

        bill("cashier@example.com", s1)
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.subtotalMinor").value(114000))
                .andExpect(jsonPath("$.ordersBilled").value(3))
                .andExpect(jsonPath("$.ordersInProgress").value(0))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.guests[1].subtotalMinor").value(46000));
        assertThat(bills.count()).isEqualTo(1);
    }

    @Test
    void aCancelledOrderTakesItsLinesOffTheBillAgain() throws Exception {
        setStatus(pending, OrderStatus.DELIVERED);
        bill("cashier@example.com", s1).andExpect(jsonPath("$.subtotalMinor").value(114000));

        setStatus(pending, OrderStatus.CANCELLED);

        bill("cashier@example.com", s1)
                .andExpect(jsonPath("$.subtotalMinor").value(102000))
                .andExpect(jsonPath("$.ordersBilled").value(2))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void aTableWithNoOrdersHasAnEmptyBillInTheHotelCurrency() throws Exception {
        String empty = session(hotelId, "t-2", "T2");

        bill("cashier@example.com", empty)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.guests.length()").value(0))
                .andExpect(jsonPath("$.subtotalMinor").value(0))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.complete").value(true));
        assertThat(bills.findByDiningSessionId(empty)).isPresent();
    }

    @Test
    void whenTheTableIsClearedTheBillIsFrozen() throws Exception {
        bill("cashier@example.com", s1).andExpect(jsonPath("$.subtotalMinor").value(102000));
        closeSession(s1);

        setStatus(pending, OrderStatus.DELIVERED); // changes after the table was cleared do not touch the bill

        bill("cashier@example.com", s1)
                .andExpect(jsonPath("$.sessionStatus").value("CLOSED"))
                .andExpect(jsonPath("$.subtotalMinor").value(102000))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.ordersInProgress").value(1));
    }

    @Test
    void aClosedTableThatNobodyLookedAtIsBilledOnTheFirstLook() throws Exception {
        closeSession(s1);
        assertThat(bills.count()).isZero();

        bill("manager@example.com", s1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(102000));
        assertThat(bills.count()).isEqualTo(1);
    }

    @Test
    void eachSessionGetsItsOwnBill() throws Exception {
        String second = session(hotelId, "t-2", "T2");

        MvcResult a = bill("cashier@example.com", s1).andReturn();
        MvcResult b = bill("cashier@example.com", second).andReturn();

        assertThat((String) JsonPath.read(a.getResponse().getContentAsString(), "$.id"))
                .isNotEqualTo(JsonPath.read(b.getResponse().getContentAsString(), "$.id"));
        assertThat(bills.count()).isEqualTo(2);
    }

    @Test
    void accessRules() throws Exception {
        bill("cashier@example.com", s1).andExpect(status().isOk());
        bill("w1@example.com", s1).andExpect(status().isOk());
        bill("manager@example.com", s1).andExpect(status().isOk());
        bill("cook@example.com", s1).andExpect(status().isForbidden());
        bill(null, s1).andExpect(status().isUnauthorized());
        bill("otherwaiter@example.com", s1).andExpect(status().isNotFound()); // not a member of this hotel
        bill("cashier@example.com", "no-such-session").andExpect(status().isNotFound());

        String foreign = session(otherHotelId, "t-9", "T9"); // a session of another hotel, asked for through ours
        bill("cashier@example.com", foreign).andExpect(status().isNotFound());
    }
}