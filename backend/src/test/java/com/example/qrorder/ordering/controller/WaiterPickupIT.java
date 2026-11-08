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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class WaiterPickupIT extends AbstractIntegrationTest {

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
    String t3;

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
        t3 = table("T3");
        String w1 = userId("w1@example.com");
        assignments.save(new TableAssignment(hotelId, t1, w1, w1, Instant.now())); // T3 stays unassigned
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

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String order(OrderStatus status) {
        Order saved = orders.save(new Order(hotelId, "ds-1", t1, "T1", "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("item-1", "Paneer Tikka", Diet.VEG, null, null, List.of(), 28000L, 2, 56000L,
                        null)),
                56000L, null, null, Instant.now().minus(2, ChronoUnit.HOURS)));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("status", status).set("statusHistory", List.of(new OrderStatusChange(
                        OrderStatus.PREPARING, status, "STAFF", "someone", null,
                        Instant.now().minus(5, ChronoUnit.MINUTES)))),
                Order.class);
        return saved.getId();
    }

    private String ready() {
        return order(OrderStatus.READY);
    }

    private ResultActions act(String email, String orderId, String action) throws Exception {
        var request = post("/api/v1/hotels/" + hotelId + "/waiter/orders/" + orderId + "/" + action);
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        return mvc.perform(request);
    }

    private ResultActions tasks(String email) throws Exception {
        return mvc.perform(get("/api/v1/hotels/" + hotelId + "/waiter/tasks?scope=all")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email)));
    }

    private Order stored(String orderId) {
        return orders.findById(orderId).orElseThrow();
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

    private void demote(String email) {
        HotelMembership membership = memberships.findByHotelIdAndUserId(hotelId, userId(email)).orElseThrow();
        membership.setRoles(Set.of(HotelRole.CASHIER));
        memberships.save(membership);
    }

    // ---------- pickup ----------

    @Test
    void aWaiterPicksUpAReadyOrderAndRepeatingItIsFine() throws Exception {
        String id = ready();

        act("w1@example.com", id, "pickup")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.pickedUpByName").value("Waiter One"))
                .andExpect(jsonPath("$.pickedUpAt").isNotEmpty());
        act("w1@example.com", id, "pickup").andExpect(status().isOk());

        assertThat(stored(id).getPickedUpByUserId()).isEqualTo(userId("w1@example.com"));
        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.READY); // pickup is not a status
    }

    @Test
    void aSecondWaiterCannotPickUpAnOrderThatIsBeingCarried() throws Exception {
        String id = ready();
        act("w1@example.com", id, "pickup").andExpect(status().isOk());

        act("w2@example.com", id, "pickup")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This order is already being carried by Waiter One"));
        assertThat(stored(id).getPickedUpByUserId()).isEqualTo(userId("w1@example.com"));
    }

    @Test
    void onlyReadyOrdersCanBePickedUp() throws Exception {
        for (OrderStatus other : List.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
                OrderStatus.DELIVERED, OrderStatus.CANCELLED)) {
            String id = order(other);
            act("w1@example.com", id, "pickup")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
            assertThat(stored(id).getPickedUpByUserId()).isNull();
        }
        act("w1@example.com", "no-such-order", "pickup").andExpect(status().isNotFound());
    }

    @Test
    void accessRules() throws Exception {
        String id = ready();
        act("cook@example.com", id, "pickup").andExpect(status().isForbidden());
        act("cashier@example.com", id, "pickup").andExpect(status().isForbidden());
        act("cook@example.com", id, "deliver").andExpect(status().isForbidden());
        act("cashier@example.com", id, "drop").andExpect(status().isForbidden());
        act(null, id, "pickup").andExpect(status().isUnauthorized());
        act("otherwaiter@example.com", id, "pickup").andExpect(status().isNotFound()); // not a member here
        assertThat(stored(id).getPickedUpByUserId()).isNull();
    }

    @Test
    void twoWaitersPickingUpTheSameOrderAtOnceExactlyOneWins() throws Exception {
        String w1 = login("w1@example.com");
        String w2 = login("w2@example.com");

        for (int round = 0; round < 10; round++) {
            String id = ready();
            String url = "/api/v1/hotels/" + hotelId + "/waiter/orders/" + id + "/pickup";
            List<Callable<Integer>> tasks = List.of(
                    () -> mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + w1))
                            .andReturn().getResponse().getStatus(),
                    () -> mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + w2))
                            .andReturn().getResponse().getStatus());

            assertThat(runTogether(tasks)).as("round %d", round).containsExactlyInAnyOrder(200, 409);
        }
    }

    // ---------- drop ----------

    @Test
    void aWaiterCanPutTheirOrderBackAndSomeoneElseCanTakeIt() throws Exception {
        String id = ready();
        act("w1@example.com", id, "pickup").andExpect(status().isOk());

        act("w1@example.com", id, "drop").andExpect(status().isNoContent());
        assertThat(stored(id).getPickedUpByUserId()).isNull();
        assertThat(stored(id).getPickedUpAt()).isNull();
        act("w1@example.com", id, "drop").andExpect(status().isNoContent()); // nothing left to drop

        act("w2@example.com", id, "pickup").andExpect(status().isOk());
        assertThat(stored(id).getPickedUpByUserId()).isEqualTo(userId("w2@example.com"));
    }

    @Test
    void aWaiterCannotDropSomeoneElsesOrderButAManagerCan() throws Exception {
        String id = ready();
        act("w1@example.com", id, "pickup").andExpect(status().isOk());

        act("w2@example.com", id, "drop").andExpect(status().isConflict());
        assertThat(stored(id).getPickedUpByUserId()).isEqualTo(userId("w1@example.com"));

        act("manager@example.com", id, "drop").andExpect(status().isNoContent());
        assertThat(stored(id).getPickedUpByUserId()).isNull();
    }

    // ---------- deliver ----------

    @Test
    void theHolderDeliversAndTheHistoryRecordsWho() throws Exception {
        String id = ready();
        act("w1@example.com", id, "pickup").andExpect(status().isOk());

        act("w1@example.com", id, "deliver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.pickedUpByName").value("Waiter One"));

        Order delivered = stored(id);
        assertThat(delivered.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        OrderStatusChange last = delivered.getStatusHistory().get(delivered.getStatusHistory().size() - 1);
        assertThat(last.to()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(last.actorId()).isEqualTo(userId("w1@example.com"));

        act("w1@example.com", id, "deliver").andExpect(status().isOk()); // repeat: same answer
        assertThat(stored(id).getStatusHistory()).hasSize(2);
        tasks("w1@example.com").andExpect(jsonPath("$.length()").value(0)); // gone from the task list
    }

    @Test
    void anyWaiterCanDeliverAnOrderNobodyPickedUp() throws Exception {
        String id = ready();
        act("w2@example.com", id, "deliver").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void anotherWaiterCannotDeliverAnOrderThatIsBeingCarriedButAManagerCan() throws Exception {
        String id = ready();
        act("w1@example.com", id, "pickup").andExpect(status().isOk());

        act("w2@example.com", id, "deliver")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This order is being carried by Waiter One"));
        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.READY);

        act("manager@example.com", id, "deliver").andExpect(status().isOk());
        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void onlyReadyOrdersCanBeDelivered() throws Exception {
        for (OrderStatus other : List.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING)) {
            String id = order(other);
            act("w1@example.com", id, "deliver").andExpect(status().isConflict());
            assertThat(stored(id).getStatus()).isEqualTo(other);
        }
        String cancelled = order(OrderStatus.CANCELLED);
        act("w1@example.com", cancelled, "deliver")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This order has been cancelled"));
        act("w1@example.com", "no-such-order", "deliver").andExpect(status().isNotFound());
    }

    @Test
    void twoWaitersDeliveringTheSameOrderAtOnceIsOneDelivery() throws Exception {
        String w1 = login("w1@example.com");
        String w2 = login("w2@example.com");

        for (int round = 0; round < 10; round++) {
            String id = ready();
            String url = "/api/v1/hotels/" + hotelId + "/waiter/orders/" + id + "/deliver";
            List<Callable<Integer>> tasks = List.of(
                    () -> mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + w1))
                            .andReturn().getResponse().getStatus(),
                    () -> mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + w2))
                            .andReturn().getResponse().getStatus());

            assertThat(runTogether(tasks)).as("round %d", round).containsExactly(200, 200);
            assertThat(stored(id).getStatusHistory().stream().filter(c -> c.to() == OrderStatus.DELIVERED).count())
                    .isEqualTo(1);
        }
    }

    // ---------- stale holders ----------

    @Test
    void anOrderHeldBySomeoneWhoNoLongerServesCanBePickedUpOrDeliveredByAnyone() throws Exception {
        String toPick = ready();
        String toDeliver = ready();
        act("w1@example.com", toPick, "pickup").andExpect(status().isOk());
        act("w1@example.com", toDeliver, "pickup").andExpect(status().isOk());

        demote("w1@example.com");

        act("w2@example.com", toPick, "pickup").andExpect(status().isOk());
        assertThat(stored(toPick).getPickedUpByUserId()).isEqualTo(userId("w2@example.com"));
        act("w2@example.com", toDeliver, "deliver").andExpect(status().isOk());
        assertThat(stored(toDeliver).getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    // ---------- the task list shows pickups ----------

    @Test
    void theTaskListShowsWhoIsCarryingWhat() throws Exception {
        String id = ready();
        act("w1@example.com", id, "pickup").andExpect(status().isOk());

        tasks("w1@example.com")
                .andExpect(jsonPath("$[0].carriedByMe").value(true))
                .andExpect(jsonPath("$[0].pickedUpByName").value("Waiter One"))
                .andExpect(jsonPath("$[0].pickedUpAt").isNotEmpty());
        tasks("w2@example.com")
                .andExpect(jsonPath("$[0].carriedByMe").value(false))
                .andExpect(jsonPath("$[0].pickedUpByName").value("Waiter One"));

        act("w1@example.com", id, "drop").andExpect(status().isNoContent());
        tasks("w2@example.com").andExpect(jsonPath("$[0].pickedUpByUserId").doesNotExist());
    }
}