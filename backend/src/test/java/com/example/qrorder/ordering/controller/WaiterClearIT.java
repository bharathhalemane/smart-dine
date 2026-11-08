package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.TableAssignment;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.TableAssignmentRepository;
import com.example.qrorder.tables.repository.WaiterEventRepository;
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

class WaiterClearIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired TableAssignmentRepository assignments;
    @Autowired DiningSessionRepository sessions;
    @Autowired OrderRepository orders;
    @Autowired WaiterEventRepository events;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String t1;
    String t2;
    String t3;
    String t4;
    String s1;
    String s2;
    String s3;
    String base;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        assignments.deleteAll();
        sessions.deleteAll();
        orders.deleteAll();
        events.deleteAll();
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
        t4 = table("T4"); // never gets a session
        assign(t1, "w1@example.com");
        assign(t2, "w2@example.com"); // T3 has nobody responsible

        s1 = session(t1, "T1");
        s2 = session(t2, "T2");
        s3 = session(t3, "T3");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(s2)),
                new Update().set("status", SessionStatus.BILL_REQUESTED), DiningSession.class);
        order(s1, t1, "T1", OrderStatus.PENDING);   // still in progress
        order(s2, t2, "T2", OrderStatus.DELIVERED); // finished
        order(s2, t2, "T2", OrderStatus.CANCELLED); // finished
        base = "/api/v1/hotels/" + hotelId + "/waiter/tables";
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

    private String userId(String email) {
        return users.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }

    private void assign(String tableId, String email) {
        String id = userId(email);
        assignments.save(new TableAssignment(hotelId, tableId, id, id, Instant.now()));
    }

    private String session(String tableId, String number) {
        return sessions.save(new DiningSession(hotelId, tableId, number, Instant.now())).getId();
    }

    private void order(String sessionId, String tableId, String number, OrderStatus status) {
        Order saved = orders.save(new Order(hotelId, sessionId, tableId, number, "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("item-1", "Paneer Tikka", Diet.VEG, null, null, List.of(), 28000L, 2, 56000L,
                        null)),
                56000L, null, null, Instant.now()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("status", status), Order.class);
    }

    private void waitersMayClear(boolean on) {
        Hotel hotel = hotels.findById(hotelId).orElseThrow();
        hotel.setWaitersMayClearTables(on);
        hotels.save(hotel);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String email) throws Exception {
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        return mvc.perform(request);
    }

    private ResultActions clear(String email, String tableId, String query) throws Exception {
        return send(post(base + "/" + tableId + "/clear" + query), email);
    }

    private ResultActions toClear(String email, String query) throws Exception {
        return send(get(base + "/to-clear" + query), email);
    }

    private boolean active(String sessionId) {
        return sessions.findById(sessionId).orElseThrow().isActive();
    }

    // ---------- tests ----------

    @Test
    void aWaiterCannotClearOrListUnlessTheHotelAllowsIt() throws Exception {
        clear("w2@example.com", t2, "").andExpect(status().isForbidden());
        toClear("w2@example.com", "").andExpect(status().isForbidden());
        assertThat(active(s2)).isTrue();

        for (String never : List.of("cook@example.com", "cashier@example.com")) {
            clear(never, t2, "").andExpect(status().isForbidden());
            toClear(never, "").andExpect(status().isForbidden());
        }
        waitersMayClear(true); // the switch does not help a cook or a cashier
        clear("cook@example.com", t2, "").andExpect(status().isForbidden());
        clear("cashier@example.com", t2, "").andExpect(status().isForbidden());

        clear(null, t2, "").andExpect(status().isUnauthorized());
        toClear(null, "").andExpect(status().isUnauthorized());
        clear("otherwaiter@example.com", t2, "").andExpect(status().isNotFound()); // not a member here
        assertThat(active(s2)).isTrue();
    }

    @Test
    void aManagerClearsAFinishedTableAndItIsLogged() throws Exception {
        clear("manager@example.com", t2, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.tableNumber").value("T2"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        assertThat(active(s2)).isFalse();

        clear("manager@example.com", t2, "").andExpect(status().isConflict()); // already free

        send(get("/api/v1/hotels/" + hotelId + "/waiter-assignments/history"), "manager@example.com")
                .andExpect(jsonPath("$[0].type").value("TABLE_CLEARED"))
                .andExpect(jsonPath("$[0].tableNumber").value("T2"))
                .andExpect(jsonPath("$[0].actorName").value("Manager Meera"))
                .andExpect(jsonPath("$[0].note").doesNotExist());
    }

    @Test
    void aWaiterWhoIsAllowedClearsAFinishedTable() throws Exception {
        waitersMayClear(true);

        clear("w2@example.com", t2, "").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        clear("w2@example.com", t3, "").andExpect(status().isOk()); // an unclaimed table with no orders
        assertThat(active(s2)).isFalse();
        assertThat(active(s3)).isFalse();

        toClear("w2@example.com", "?scope=all")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tableNumber").value("T1")); // only T1 is still live
    }

    @Test
    void aTableWithOrdersInProgressIsNotClearedAndOnlyAManagerCanForceIt() throws Exception {
        waitersMayClear(true);

        clear("w1@example.com", t1, "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("This table still has 1 order in progress"));
        clear("manager@example.com", t1, "").andExpect(status().isConflict());
        clear("w1@example.com", t1, "?force=true")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only a manager can clear a table with orders in progress"));
        assertThat(active(s1)).isTrue();

        order(s1, t1, "T1", OrderStatus.READY);
        clear("manager@example.com", t1, "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This table still has 2 orders in progress"));

        clear("manager@example.com", t1, "?force=true").andExpect(status().isOk());
        assertThat(active(s1)).isFalse();

        send(get("/api/v1/hotels/" + hotelId + "/waiter-assignments/history"), "manager@example.com")
                .andExpect(jsonPath("$[0].type").value("TABLE_CLEARED"))
                .andExpect(jsonPath("$[0].note").value("Cleared with 2 orders in progress"));
    }

    @Test
    void forcingATableThatHasNothingInProgressIsHarmlessForAWaiter() throws Exception {
        waitersMayClear(true);
        clear("w2@example.com", t2, "?force=true").andExpect(status().isOk());
        assertThat(active(s2)).isFalse();
    }

    @Test
    void aTableWithoutASessionOrThatDoesNotExistCannotBeCleared() throws Exception {
        clear("manager@example.com", t4, "")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This table is already free"));
        clear("manager@example.com", "no-such-table", "").andExpect(status().isNotFound());
    }

    @Test
    void theListShowsLiveTablesWithWhatBlocksThem() throws Exception {
        waitersMayClear(true);

        toClear("w2@example.com", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tableNumber").value("T2"))
                .andExpect(jsonPath("$[0].sessionStatus").value("BILL_REQUESTED"))
                .andExpect(jsonPath("$[0].readyToClear").value(true))
                .andExpect(jsonPath("$[0].ordersInProgress").value(0))
                .andExpect(jsonPath("$[0].blockedReason").doesNotExist())
                .andExpect(jsonPath("$[0].mine").value(true))
                .andExpect(jsonPath("$[1].tableNumber").value("T3"))
                .andExpect(jsonPath("$[1].unassigned").value(true))
                .andExpect(jsonPath("$[1].sessionStatus").value("OPEN"));

        toClear("w2@example.com", "?scope=all")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tableNumber").value("T1"))
                .andExpect(jsonPath("$[0].readyToClear").value(false))
                .andExpect(jsonPath("$[0].ordersInProgress").value(1))
                .andExpect(jsonPath("$[0].blockedReason").value("1 order in progress"))
                .andExpect(jsonPath("$[0].waiterName").value("Waiter One"))
                .andExpect(jsonPath("$[0].mine").value(false));

        toClear("w1@example.com", "").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tableNumber").value("T1"));
        toClear("manager@example.com", "").andExpect(jsonPath("$.length()").value(1)); // only the unclaimed T3
        toClear("manager@example.com", "?scope=all").andExpect(jsonPath("$.length()").value(3));
        toClear("w1@example.com", "?scope=everyone")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}