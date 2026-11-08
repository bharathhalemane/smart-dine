package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.TableAssignment;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.TableAssignmentRepository;
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

class WaiterTaskIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired TableAssignmentRepository assignments;
    @Autowired OrderRepository orders;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String t1;
    String t2;
    String t3;
    String url;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        assignments.deleteAll();
        orders.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "w2@example.com", "Waiter Two", HotelRole.WAITER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(otherHotelId, "otherwaiter@example.com", "Other Waiter", HotelRole.WAITER);
        t1 = table("T1");
        t2 = table("T2");
        t3 = table("T3");
        url = "/api/v1/hotels/" + hotelId + "/waiter/tasks";
        assign(t1, "w1@example.com");
        assign(t2, "w2@example.com");
        // T3 has nobody responsible
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String table(String number) {
        return tables.save(new DiningTable(hotelId, number, 4, null, Instant.now())).getId();
    }

    private void assign(String tableId, String email) {
        String id = users.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow().getId();
        assignments.save(new TableAssignment(hotelId, tableId, id, id, Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions tasks(String email, String query) throws Exception {
        var request = get(url + query);
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        return mvc.perform(request);
    }

    private OrderLine line(String name) {
        return new OrderLine("item-1", name, Diet.VEG, null, null, List.of(), 28000L, 2, 56000L, null);
    }

    /** An order at the table, in the given status; if ready, it became ready the given number of minutes ago. */
    private String order(String tableId, String tableNumber, OrderStatus status, long readyMinutesAgo,
                         OrderLine... lines) {
        Order saved = orders.save(new Order(hotelId, "ds-1", tableId, tableNumber, "cust-1", "Guest 1", "INR",
                List.of(lines), 56000L, null, null, Instant.now().minus(2, ChronoUnit.HOURS)));
        Instant at = Instant.now().minus(readyMinutesAgo, ChronoUnit.MINUTES);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("status", status).set("statusHistory",
                        List.of(new OrderStatusChange(OrderStatus.PREPARING, status, "STAFF", "someone", null, at))),
                Order.class);
        return saved.getId();
    }

    // ---------- tests ----------

    @Test
    void aWaiterSeesReadyOrdersOfTheirTablesAndUnclaimedTablesOldestFirst() throws Exception {
        String mineOld = order(t1, "T1", OrderStatus.READY, 12, line("Paneer Tikka"));
        String mineNew = order(t1, "T1", OrderStatus.READY, 3, line("Naan"));
        String unclaimed = order(t3, "T3", OrderStatus.READY, 20, line("Dosa"));
        order(t2, "T2", OrderStatus.READY, 5, line("Biryani")); // someone else's table

        tasks("w1@example.com", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].orderId").value(unclaimed))
                .andExpect(jsonPath("$[0].unassigned").value(true))
                .andExpect(jsonPath("$[0].mine").value(false))
                .andExpect(jsonPath("$[0].waiterUserId").doesNotExist())
                .andExpect(jsonPath("$[1].orderId").value(mineOld))
                .andExpect(jsonPath("$[1].mine").value(true))
                .andExpect(jsonPath("$[1].unassigned").value(false))
                .andExpect(jsonPath("$[1].waiterName").value("Waiter One"))
                .andExpect(jsonPath("$[1].tableNumber").value("T1"))
                .andExpect(jsonPath("$[1].customerLabel").value("Guest 1"))
                .andExpect(jsonPath("$[1].lines[0].itemName").value("Paneer Tikka"))
                .andExpect(jsonPath("$[2].orderId").value(mineNew));
    }

    @Test
    void waitingTimeIsMeasuredFromWhenTheOrderBecameReady() throws Exception {
        order(t1, "T1", OrderStatus.READY, 10, line("Paneer Tikka"));

        String body = tasks("w1@example.com", "").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        long waiting = ((Number) JsonPath.read(body, "$[0].waitingSeconds")).longValue();

        assertThat(waiting).isBetween(600L, 660L);
        assertThat((String) JsonPath.read(body, "$[0].readyAt")).isNotBlank();
    }

    @Test
    void onlyReadyOrdersAreTasks() throws Exception {
        for (OrderStatus other : List.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
                OrderStatus.DELIVERED, OrderStatus.CANCELLED)) {
            order(t1, "T1", other, 5, line("Not ready: " + other));
        }
        String ready = order(t1, "T1", OrderStatus.READY, 5, line("Ready one"));

        tasks("w1@example.com", "")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(ready));
    }

    @Test
    void scopeAllShowsEveryReadyOrderWithItsWaiter() throws Exception {
        order(t1, "T1", OrderStatus.READY, 12, line("Paneer Tikka"));
        order(t2, "T2", OrderStatus.READY, 8, line("Biryani"));
        order(t3, "T3", OrderStatus.READY, 4, line("Dosa"));

        tasks("w1@example.com", "?scope=all")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tableNumber").value("T1"))
                .andExpect(jsonPath("$[0].mine").value(true))
                .andExpect(jsonPath("$[1].tableNumber").value("T2"))
                .andExpect(jsonPath("$[1].waiterName").value("Waiter Two"))
                .andExpect(jsonPath("$[1].mine").value(false))
                .andExpect(jsonPath("$[2].unassigned").value(true));

        // the second waiter's own view leaves out the first waiter's table
        tasks("w2@example.com", "")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tableNumber").value("T2"))
                .andExpect(jsonPath("$[1].tableNumber").value("T3"));

        // a manager can serve too, and sees the unclaimed table in their own view
        tasks("manager@example.com", "").andExpect(jsonPath("$.length()").value(1));
        tasks("manager@example.com", "?scope=all").andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void linesTheKitchenMarkedUnavailableAreLeftOut() throws Exception {
        OrderLine gone = new OrderLine("item-2", "Out of stock", Diet.VEG, null, null, List.of(), 10000L, 1,
                10000L, null).markUnavailable("Ran out");
        order(t1, "T1", OrderStatus.READY, 5, line("Paneer Tikka"), gone);

        tasks("w1@example.com", "")
                .andExpect(jsonPath("$[0].lines.length()").value(1))
                .andExpect(jsonPath("$[0].lines[0].itemName").value("Paneer Tikka"));
    }

    @Test
    void anAssignmentToSomeoneWhoNoLongerServesCountsAsUnclaimed() throws Exception {
        order(t2, "T2", OrderStatus.READY, 5, line("Biryani"));
        HotelMembership membership = memberships.findByHotelIdAndUserId(hotelId,
                users.findAll().stream().filter(u -> u.getEmail().equals("w2@example.com")).findFirst().orElseThrow()
                        .getId()).orElseThrow();
        membership.setRoles(Set.of(HotelRole.CASHIER));
        memberships.save(membership);

        tasks("w1@example.com", "")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].unassigned").value(true))
                .andExpect(jsonPath("$[0].waiterUserId").doesNotExist());
    }

    @Test
    void ordersOfOtherHotelsAreNeverShown() throws Exception {
        orders.save(new Order(otherHotelId, "ds-9", "other-table", "T1", "cust-9", "Guest 1", "INR",
                List.of(line("Secret")), 56000L, null, null, Instant.now()));
        mongo.updateMulti(Query.query(Criteria.where("hotelId").is(otherHotelId)),
                new Update().set("status", OrderStatus.READY), Order.class);

        tasks("w1@example.com", "?scope=all").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void accessRulesAndBadScope() throws Exception {
        tasks("cook@example.com", "").andExpect(status().isForbidden());
        tasks("cashier@example.com", "?scope=all").andExpect(status().isForbidden());
        tasks(null, "").andExpect(status().isUnauthorized());
        tasks("otherwaiter@example.com", "").andExpect(status().isNotFound()); // not a member of this hotel
        tasks("w1@example.com", "?scope=everyone")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        tasks("w1@example.com", "").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}