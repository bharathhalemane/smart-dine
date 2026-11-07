package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
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

class KitchenQueueIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired OrderRepository orders;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        orders.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "owner@example.com", HotelRole.OWNER);
        member(hotelId, "manager@example.com", HotelRole.MANAGER);
        member(hotelId, "cook@example.com", HotelRole.KITCHEN);
        member(hotelId, "waiter@example.com", HotelRole.WAITER);
        member(hotelId, "cashier@example.com", HotelRole.CASHIER);
        member(otherHotelId, "othercook@example.com", HotelRole.KITCHEN);
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
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

    private String userId(String email) {
        return users.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private String url(String hotel, String suffix) {
        return "/api/v1/hotels/" + hotel + "/kitchen/orders" + (suffix == null ? "" : suffix);
    }

    /** Saves an order in the given state, created the given number of minutes ago. */
    private String orderIn(String hotel, OrderStatus status, int minutesAgo, String table) {
        Order order = new Order(hotel, "ds-1", "t-1", table, "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("item-1", "Paneer Tikka", Diet.VEG, null, null, List.of(), 28000L, 2, 56000L,
                        "less spicy")),
                56000L, null, null, Instant.now().minus(minutesAgo, ChronoUnit.MINUTES));
        String id = orders.save(order).getId();
        if (status != OrderStatus.PENDING) {
            mongo.updateFirst(Query.query(where("_id").is(id)), new Update().set("status", status), Order.class);
        }
        return id;
    }

    private String orderIn(OrderStatus status) {
        return orderIn(hotelId, status, 1, "T1");
    }

    private OrderStatus statusOf(String id) {
        return orders.findById(id).orElseThrow().getStatus();
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
    void theQueueIsOldestFirstOpenOrdersOnlyForThisHotelAndForKitchenAndWaiters() throws Exception {
        String newest = orderIn(hotelId, OrderStatus.PENDING, 1, "T3");
        String oldest = orderIn(hotelId, OrderStatus.PENDING, 30, "T1");
        String middle = orderIn(hotelId, OrderStatus.READY, 10, "T2");
        orderIn(hotelId, OrderStatus.DELIVERED, 40, "T4");
        orderIn(hotelId, OrderStatus.CANCELLED, 50, "T5");
        orderIn(otherHotelId, OrderStatus.PENDING, 60, "T9");

        call(get(url(hotelId, null)), login("cook@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(oldest))
                .andExpect(jsonPath("$[1].id").value(middle))
                .andExpect(jsonPath("$[2].id").value(newest))
                .andExpect(jsonPath("$[0].tableNumber").value("T1"))
                .andExpect(jsonPath("$[0].customerLabel").value("Guest 1"))
                .andExpect(jsonPath("$[0].lines[0].itemName").value("Paneer Tikka"))
                .andExpect(jsonPath("$[0].lines[0].note").value("less spicy"))
                .andExpect(jsonPath("$[0].statusSince").isNotEmpty())
                .andExpect(jsonPath("$[0].hotelId").doesNotExist());

        call(get(url(hotelId, null)), login("waiter@example.com")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        call(get(url(hotelId, null)), login("manager@example.com")).andExpect(status().isOk());
        call(get(url(hotelId, null)), login("owner@example.com")).andExpect(status().isOk());
        call(get(url(hotelId, null)), login("cashier@example.com")).andExpect(status().isForbidden());
        call(get(url(hotelId, null)), null).andExpect(status().isUnauthorized());
        call(get(url(hotelId, null)), login("othercook@example.com")).andExpect(status().isNotFound());
    }

    @Test
    void theQueueCanBeNarrowedByStatusButOnlyToOpenStatuses() throws Exception {
        orderIn(hotelId, OrderStatus.PENDING, 5, "T1");
        orderIn(hotelId, OrderStatus.ACCEPTED, 4, "T2");
        orderIn(hotelId, OrderStatus.READY, 3, "T3");
        String token = login("cook@example.com");

        call(get(url(hotelId, "?status=READY")), token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("READY"));
        call(get(url(hotelId, "?status=PENDING,ACCEPTED")), token)
                .andExpect(jsonPath("$.length()").value(2));
        call(get(url(hotelId, "?status=pending&status=ready")), token)
                .andExpect(jsonPath("$.length()").value(2));

        for (String bad : List.of("DELIVERED", "CANCELLED", "bogus", "PENDING,bogus")) {
            call(get(url(hotelId, "?status=" + bad)), token)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void theKitchenWalksAnOrderThroughAcceptStartAndReadyAndEveryStepIsRecorded() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String token = login("cook@example.com");

        call(post(url(hotelId, "/" + id + "/accept")), token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.tableNumber").value("T1")).andExpect(jsonPath("$.hotelId").doesNotExist());
        call(post(url(hotelId, "/" + id + "/start")), token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PREPARING"));
        call(post(url(hotelId, "/" + id + "/ready")), token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));

        Order order = orders.findById(id).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY);
        assertThat(order.getStatusHistory()).hasSize(3);
        assertThat(order.getStatusHistory()).allSatisfy(change -> {
            assertThat(change.actorType()).isEqualTo("STAFF");
            assertThat(change.actorId()).isEqualTo(userId("cook@example.com"));
        });
        assertThat(order.getStatusHistory()).extracting(c -> c.to())
                .containsExactly(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY);
    }

    @Test
    void stepsCannotBeSkippedOrRunBackwards() throws Exception {
        String pending = orderIn(OrderStatus.PENDING);
        String accepted = orderIn(OrderStatus.ACCEPTED);
        String preparing = orderIn(OrderStatus.PREPARING);
        String token = login("manager@example.com");

        call(post(url(hotelId, "/" + pending + "/start")), token)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        call(post(url(hotelId, "/" + pending + "/ready")), token).andExpect(status().isConflict());
        call(post(url(hotelId, "/" + accepted + "/ready")), token).andExpect(status().isConflict());
        call(post(url(hotelId, "/" + preparing + "/deliver")), token).andExpect(status().isConflict());
        call(post(url(hotelId, "/" + preparing + "/accept")), token).andExpect(status().isConflict());

        assertThat(statusOf(pending)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(accepted)).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(statusOf(preparing)).isEqualTo(OrderStatus.PREPARING);
        assertThat(orders.findById(pending).orElseThrow().getStatusHistory()).isEmpty();
    }

    @Test
    void repeatingAStepIsHarmless() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String token = login("cook@example.com");

        call(post(url(hotelId, "/" + id + "/accept")), token).andExpect(status().isOk());
        call(post(url(hotelId, "/" + id + "/accept")), token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(orders.findById(id).orElseThrow().getStatusHistory()).hasSize(1);
    }

    @Test
    void permissionsFollowTheRoles() throws Exception {
        String pending = orderIn(OrderStatus.PENDING);
        String accepted = orderIn(OrderStatus.ACCEPTED);
        String preparing = orderIn(OrderStatus.PREPARING);
        String ready = orderIn(OrderStatus.READY);
        String waiter = login("waiter@example.com");
        String cook = login("cook@example.com");
        String cashier = login("cashier@example.com");

        // waiters deliver, they do not cook
        call(post(url(hotelId, "/" + pending + "/accept")), waiter).andExpect(status().isForbidden());
        call(post(url(hotelId, "/" + accepted + "/start")), waiter).andExpect(status().isForbidden());
        call(post(url(hotelId, "/" + preparing + "/ready")), waiter).andExpect(status().isForbidden());
        // the kitchen cooks, it does not deliver
        call(post(url(hotelId, "/" + ready + "/deliver")), cook).andExpect(status().isForbidden());
        // cashiers do neither
        call(post(url(hotelId, "/" + pending + "/accept")), cashier).andExpect(status().isForbidden());
        call(post(url(hotelId, "/" + ready + "/deliver")), cashier).andExpect(status().isForbidden());
        // nothing moved
        assertThat(statusOf(pending)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(accepted)).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(statusOf(preparing)).isEqualTo(OrderStatus.PREPARING);
        assertThat(statusOf(ready)).isEqualTo(OrderStatus.READY);

        // the right roles succeed, and managers and owners can do everything
        call(post(url(hotelId, "/" + ready + "/deliver")), waiter)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
        call(post(url(hotelId, "/" + pending + "/accept")), login("manager@example.com")).andExpect(status().isOk());
        call(post(url(hotelId, "/" + accepted + "/start")), login("owner@example.com")).andExpect(status().isOk());
    }

    @Test
    void anOrderTheCustomerCancelledCannotBeAccepted() throws Exception {
        String id = orderIn(OrderStatus.CANCELLED);

        call(post(url(hotelId, "/" + id + "/accept")), login("cook@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.detail").value("This order has been cancelled"));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void anotherHotelsStaffCannotTouchTheOrderAndUnknownIdsAreNotFound() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String foreignCook = login("othercook@example.com");
        String cook = login("cook@example.com");

        call(post(url(hotelId, "/" + id + "/accept")), foreignCook).andExpect(status().isNotFound());   // not a member here
        call(post(url(otherHotelId, "/" + id + "/accept")), foreignCook).andExpect(status().isNotFound()); // order is not theirs
        call(post(url(hotelId, "/does-not-exist/accept")), cook).andExpect(status().isNotFound());

        assertThat(statusOf(id)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void twoCooksTappingAcceptTogetherBothSucceedButItIsRecordedOnce() throws Exception {
        String id = orderIn(OrderStatus.PENDING);
        String cook = login("cook@example.com");
        String manager = login("manager@example.com");

        List<Callable<Integer>> tasks = List.of(
                () -> call(post(url(hotelId, "/" + id + "/accept")), cook).andReturn().getResponse().getStatus(),
                () -> call(post(url(hotelId, "/" + id + "/accept")), manager).andReturn().getResponse().getStatus());
        assertThat(runTogether(tasks)).containsOnly(200);

        Order order = orders.findById(id).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(order.getStatusHistory()).hasSize(1);
    }

    @Test
    void deliveredOrdersLeaveTheQueue() throws Exception {
        String id = orderIn(OrderStatus.READY);
        String waiter = login("waiter@example.com");

        call(get(url(hotelId, null)), waiter).andExpect(jsonPath("$.length()").value(1));
        call(post(url(hotelId, "/" + id + "/deliver")), waiter).andExpect(status().isOk());
        call(get(url(hotelId, null)), waiter).andExpect(jsonPath("$.length()").value(0));
    }
}