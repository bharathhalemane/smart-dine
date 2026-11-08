package com.example.qrorder.tables.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

class WaiterAssignmentIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired TableAssignmentRepository assignments;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String t1;
    String t2;
    String t3;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        assignments.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "owner@example.com", "Owner Asha", HotelRole.OWNER);
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "w2@example.com", "Waiter Two", HotelRole.WAITER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(otherHotelId, "otherwaiter@example.com", "Other Waiter", HotelRole.WAITER);
        t1 = table("T1");
        t2 = table("T2");
        t3 = table("T3");
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

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String userId(String email) {
        return users.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String waiterUrl(String tableId, String suffix) {
        return "/api/v1/hotels/" + hotelId + "/tables/" + tableId + "/waiter" + (suffix == null ? "" : suffix);
    }

    private ResultActions assign(String bearer, String tableId, String userId) throws Exception {
        return call(put(waiterUrl(tableId, null)), bearer, "{\"userId\":\"%s\"}".formatted(userId));
    }

    private String assignedTo(String tableId) {
        return assignments.findByTableId(tableId).map(TableAssignment::getWaiterUserId).orElse(null);
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- tests ----------

    @Test
    void aManagerAssignsAWaiterAndLaterOverridesIt() throws Exception {
        String manager = login("manager@example.com");

        assign(manager, t1, userId("w1@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableNumber").value("T1"))
                .andExpect(jsonPath("$.waiterUserId").value(userId("w1@example.com")))
                .andExpect(jsonPath("$.waiterName").value("Waiter One"))
                .andExpect(jsonPath("$.assignedAt").isNotEmpty());
        assertThat(assignedTo(t1)).isEqualTo(userId("w1@example.com"));

        // override: a manager moves the table to someone else, the owner can do it too
        assign(manager, t1, userId("w2@example.com")).andExpect(jsonPath("$.waiterName").value("Waiter Two"));
        assign(login("owner@example.com"), t1, userId("w1@example.com")).andExpect(status().isOk());

        assertThat(assignments.count()).isEqualTo(1); // still exactly one assignment for the table
        assertThat(assignedTo(t1)).isEqualTo(userId("w1@example.com"));
        assertThat(assignments.findByTableId(t1).orElseThrow().getAssignedByUserId())
                .isEqualTo(userId("owner@example.com"));
    }

    @Test
    void onlyPeopleWhoCanServeTablesCanBeAssigned() throws Exception {
        String manager = login("manager@example.com");

        for (String notAWaiter : List.of(userId("cook@example.com"), userId("cashier@example.com"),
                userId("otherwaiter@example.com"), "no-such-user")) {
            assign(manager, t1, notAWaiter)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(assignments.count()).isZero();

        // a manager holds the waiter permission too, so can take a table
        assign(manager, t1, userId("manager@example.com")).andExpect(status().isOk());
    }

    @Test
    void assignmentIsForManagersOnlyAndOnlyForActiveTablesOfThisHotel() throws Exception {
        String w1 = userId("w1@example.com");

        assign(login("w2@example.com"), t1, w1).andExpect(status().isForbidden());
        assign(login("cook@example.com"), t1, w1).andExpect(status().isForbidden());
        assign(login("cashier@example.com"), t1, w1).andExpect(status().isForbidden());
        assign(null, t1, w1).andExpect(status().isUnauthorized());
        assign(login("otherwaiter@example.com"), t1, w1).andExpect(status().isNotFound()); // not a member here
        assertThat(assignments.count()).isZero();

        String manager = login("manager@example.com");
        assign(manager, "no-such-table", w1).andExpect(status().isNotFound());
        mongo.updateFirst(Query.query(where("_id").is(t3)), new Update().set("active", false), DiningTable.class);
        assign(manager, t3, w1).andExpect(status().isNotFound());
        call(post(waiterUrl(t3, "/claim")), login("w1@example.com"), null).andExpect(status().isNotFound());
        assertThat(assignments.count()).isZero();
    }

    @Test
    void aManagerCanClearAnAssignmentAndRepeatingItIsHarmless() throws Exception {
        assign(login("manager@example.com"), t1, userId("w1@example.com")).andExpect(status().isOk());

        call(delete(waiterUrl(t1, null)), login("w1@example.com"), null).andExpect(status().isForbidden());
        assertThat(assignedTo(t1)).isNotNull();

        call(delete(waiterUrl(t1, null)), login("manager@example.com"), null).andExpect(status().isNoContent());
        call(delete(waiterUrl(t1, null)), login("manager@example.com"), null).andExpect(status().isNoContent());
        assertThat(assignedTo(t1)).isNull();
    }

    @Test
    void aWaiterCanClaimAFreeTableButNotOneThatIsTaken() throws Exception {
        String w1 = login("w1@example.com");
        String w2 = login("w2@example.com");

        call(post(waiterUrl(t1, "/claim")), w1, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.waiterName").value("Waiter One"));
        call(post(waiterUrl(t1, "/claim")), w1, null).andExpect(status().isOk()); // already yours: fine
        call(post(waiterUrl(t1, "/claim")), w2, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("This table is already taken by Waiter One"));
        call(post(waiterUrl(t2, "/claim")), w2, null).andExpect(status().isOk());

        call(post(waiterUrl(t3, "/claim")), login("cook@example.com"), null).andExpect(status().isForbidden());
        call(post(waiterUrl(t3, "/claim")), login("cashier@example.com"), null).andExpect(status().isForbidden());
        call(post(waiterUrl(t3, "/claim")), null, null).andExpect(status().isUnauthorized());

        assertThat(assignedTo(t1)).isEqualTo(userId("w1@example.com"));
        assertThat(assignedTo(t2)).isEqualTo(userId("w2@example.com"));
        assertThat(assignedTo(t3)).isNull();
    }

    @Test
    void aWaiterCanReleaseOnlyTheirOwnTable() throws Exception {
        String w1 = login("w1@example.com");
        String w2 = login("w2@example.com");
        call(post(waiterUrl(t1, "/claim")), w1, null).andExpect(status().isOk());
        call(post(waiterUrl(t2, "/claim")), w2, null).andExpect(status().isOk());

        call(post(waiterUrl(t2, "/release")), w1, null)
                .andExpect(status().isConflict()); // someone else's table
        assertThat(assignedTo(t2)).isEqualTo(userId("w2@example.com"));

        call(post(waiterUrl(t1, "/release")), w1, null).andExpect(status().isNoContent());
        assertThat(assignedTo(t1)).isNull();
        call(post(waiterUrl(t1, "/release")), w1, null).andExpect(status().isNoContent()); // nothing to release
        call(post(waiterUrl(t3, "/release")), w1, null).andExpect(status().isNoContent()); // free table
        call(post(waiterUrl(t1, "/release")), login("cook@example.com"), null).andExpect(status().isForbidden());
    }

    @Test
    void anAssignmentToSomeoneWhoNoLongerServesBecomesFreeAndCanBeTakenOver() throws Exception {
        assign(login("manager@example.com"), t1, userId("w1@example.com")).andExpect(status().isOk());

        // w1 is moved off waiting duty
        HotelMembership membership = memberships.findByHotelIdAndUserId(hotelId, userId("w1@example.com")).orElseThrow();
        membership.setRoles(Set.of(HotelRole.CASHIER));
        memberships.save(membership);

        call(get("/api/v1/hotels/" + hotelId + "/waiter-assignments"), login("manager@example.com"), null)
                .andExpect(jsonPath("$[0].tableNumber").value("T1"))
                .andExpect(jsonPath("$[0].waiterUserId").doesNotExist());

        call(post(waiterUrl(t1, "/claim")), login("w2@example.com"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.waiterName").value("Waiter Two"));
        assertThat(assignedTo(t1)).isEqualTo(userId("w2@example.com"));
        assertThat(assignments.count()).isEqualTo(1);
    }

    @Test
    void theListShowsEveryActiveTableInOrderAndMineShowsOnlyMyTables() throws Exception {
        String manager = login("manager@example.com");
        assign(manager, t1, userId("w1@example.com")).andExpect(status().isOk());
        assign(manager, t2, userId("w1@example.com")).andExpect(status().isOk());
        String inactive = table("T4");
        mongo.updateFirst(Query.query(where("_id").is(inactive)), new Update().set("active", false), DiningTable.class);
        String listUrl = "/api/v1/hotels/" + hotelId + "/waiter-assignments";

        call(get(listUrl), manager, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tableNumber").value("T1"))
                .andExpect(jsonPath("$[0].waiterName").value("Waiter One"))
                .andExpect(jsonPath("$[1].tableNumber").value("T2"))
                .andExpect(jsonPath("$[1].waiterName").value("Waiter One"))
                .andExpect(jsonPath("$[2].tableNumber").value("T3"))
                .andExpect(jsonPath("$[2].waiterUserId").doesNotExist());

        call(get(listUrl), login("w2@example.com"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        call(get(listUrl), login("cook@example.com"), null).andExpect(status().isForbidden());
        call(get(listUrl), null, null).andExpect(status().isUnauthorized());

        call(get(listUrl + "/mine"), login("w1@example.com"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tableNumber").value("T1")).andExpect(jsonPath("$[1].tableNumber").value("T2"));
        call(get(listUrl + "/mine"), login("w2@example.com"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call(get(listUrl + "/mine"), login("cashier@example.com"), null).andExpect(status().isForbidden());
    }

    @Test
    void twoWaitersClaimingTheSameTableAtOnceExactlyOneWins() throws Exception {
        String w1 = login("w1@example.com");
        String w2 = login("w2@example.com");

        for (int round = 0; round < 10; round++) {
            String table = table("R" + round);
            List<Callable<Integer>> tasks = List.of(
                    () -> call(post(waiterUrl(table, "/claim")), w1, null).andReturn().getResponse().getStatus(),
                    () -> call(post(waiterUrl(table, "/claim")), w2, null).andReturn().getResponse().getStatus());

            List<Integer> statuses = runTogether(tasks);

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            assertThat(assignments.findAll().stream().filter(a -> a.getTableId().equals(table)).count()).isEqualTo(1);
        }
    }
}