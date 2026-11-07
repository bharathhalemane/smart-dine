package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

class CustomerOrderIT extends AbstractIntegrationTest {

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

    String hotelId;
    String soup;
    String lassi;
    String anita;  // cookie of the first diner
    String ravi;   // cookie of a second diner at the same table

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
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
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

    /** Places a one-item order as the given diner and returns its id. */
    private String place(String cookie, String itemId) throws Exception {
        String body = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(itemId))
                        .cookie(new Cookie("qr_customer", cookie)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions getJson(String url, String cookie) throws Exception {
        var request = get(url);
        if (cookie != null) {
            request.cookie(new Cookie("qr_customer", cookie));
        }
        return mvc.perform(request);
    }

    private ResultActions cancel(String cookie, String orderId) throws Exception {
        var request = post("/api/v1/customer/orders/" + orderId + "/cancel");
        if (cookie != null) {
            request.cookie(new Cookie("qr_customer", cookie));
        }
        return mvc.perform(request);
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

    // ---------- tests ----------

    @Test
    void aDinerSeesOnlyTheirOwnOrdersNewestFirst() throws Exception {
        String first = place(anita, soup);
        String second = place(anita, lassi);
        String ravisOrder = place(ravi, soup);

        getJson("/api/v1/customer/orders", anita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].lines[0].itemName").value("Lassi"));
        getJson("/api/v1/customer/orders", ravi)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ravisOrder));
    }

    @Test
    void oneOrderIsVisibleOnlyToItsOwner() throws Exception {
        String id = place(anita, soup);

        getJson("/api/v1/customer/orders/" + id, anita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.subtotalMinor").value(15000))
                .andExpect(jsonPath("$.customerLabel").value("Guest 1"));

        // another diner at the same table, and a made-up id, look exactly the same
        getJson("/api/v1/customer/orders/" + id, ravi)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getJson("/api/v1/customer/orders/does-not-exist", anita)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        getJson("/api/v1/customer/orders/" + id, null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getJson("/api/v1/customer/orders", null).andExpect(status().isUnauthorized());
    }

    @Test
    void aPendingOrderCanBeCancelled() throws Exception {
        String id = place(anita, soup);

        cancel(anita, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        Order order = stored(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo("CUSTOMER");
        assertThat(order.getCancelledAt()).isNotNull();
        getJson("/api/v1/customer/orders/" + id, anita).andExpect(jsonPath("$.status").value("CANCELLED"));
        // the session still counts the order as placed
        assertThat(sessions.findAll().get(0).getOrderCount()).isEqualTo(1);
    }

    @Test
    void cancellingTwiceIsHarmless() throws Exception {
        String id = place(anita, soup);
        cancel(anita, id).andExpect(status().isOk());
        Instant firstCancelledAt = stored(id).getCancelledAt();

        cancel(anita, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(stored(id).getCancelledAt()).isEqualTo(firstCancelledAt);
    }

    @Test
    void anOrderTheKitchenHasAcceptedCannotBeCancelled() throws Exception {
        String id = place(anita, soup);
        mongo.updateFirst(Query.query(where("_id").is(id)), new Update().set("status", OrderStatus.ACCEPTED),
                Order.class);

        cancel(anita, id)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("This order can no longer be cancelled because it is accepted"));

        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(stored(id).getCancelledAt()).isNull();
    }

    @Test
    void aDinerCannotCancelSomeoneElsesOrder() throws Exception {
        String id = place(anita, soup);

        cancel(ravi, id).andExpect(status().isNotFound());
        cancel(null, id).andExpect(status().isUnauthorized());

        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void aCancelRacingTheKitchenAcceptanceHasExactlyOneWinner() throws Exception {
        for (int round = 0; round < 10; round++) {
            String id = place(anita, soup);

            // the kitchen's accept, written the way Phase 8 will: only if still PENDING
            Callable<Integer> accept = () -> mongo.findAndModify(
                    Query.query(where("_id").is(id).and("status").is(OrderStatus.PENDING)),
                    new Update().set("status", OrderStatus.ACCEPTED), Order.class) != null ? 1 : 0;
            Callable<Integer> customerCancel = () -> cancel(anita, id).andReturn().getResponse().getStatus();

            List<Integer> results = runTogether(List.of(accept, customerCancel));
            boolean kitchenWon = results.get(0) == 1;
            int cancelStatus = results.get(1);

            if (kitchenWon) {
                assertThat(cancelStatus).as("round %d: kitchen won, so the cancel is refused", round).isEqualTo(409);
                assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.ACCEPTED);
            } else {
                assertThat(cancelStatus).as("round %d: customer won", round).isEqualTo(200);
                assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            }
        }
    }

    @Test
    void cancellingStillWorksWhileTheBillIsRequestedButNotAfterTheTableCloses() throws Exception {
        String first = place(anita, soup);
        String second = place(anita, lassi);
        String visitId = sessions.findAll().get(0).getId();

        mongo.updateFirst(Query.query(where("_id").is(visitId)), new Update().set("status", "BILL_REQUESTED"),
                DiningSession.class);
        cancel(anita, first).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        mongo.updateFirst(Query.query(where("_id").is(visitId)),
                new Update().set("status", "CLOSED").set("active", false), DiningSession.class);
        cancel(anita, second)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));

        assertThat(stored(second).getStatus()).isEqualTo(OrderStatus.PENDING);
    }
}