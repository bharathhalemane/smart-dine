package com.example.qrorder.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.event.FloorChanged;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeEventsIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired SimpMessagingTemplate broker;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;
    @Autowired MenuCategoryRepository categories;
    @Autowired MenuItemRepository items;
    @Autowired OrderRepository orders;
    @Autowired BillRepository bills;
    @Autowired PaymentRepository payments;
    @Autowired PaymentSettingsRepository paymentSettings;
    @Autowired PasswordEncoder encoder;

    WebSocketStompClient client;
    ThreadPoolTaskScheduler scheduler;
    String hotelId;
    String otherHotelId;
    String soupId;
    String guest;
    String sessionId;
    String tableId;
    int keyCounter;

    /** Everything one subscription has received, so a test can ask "did X arrive" and "did Y never arrive". */
    static final class Listener {
        final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();

        boolean saw(Predicate<Map<String, Object>> match) {
            return received.stream().anyMatch(match);
        }

        Map<String, Object> await(String type, String status) throws InterruptedException {
            Predicate<Map<String, Object>> match = m -> type.equals(m.get("type"))
                    && (status == null || status.equals(m.get("status")));
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                for (Map<String, Object> m : received) {
                    if (match.test(m)) {
                        return m;
                    }
                }
                Thread.sleep(50);
            }
            return null;
        }

        List<Map<String, Object>> real() {
            return received.stream().filter(m -> !"PING".equals(m.get("type"))).toList();
        }
    }

    static final class Probe extends StompSessionHandlerAdapter {
        final CountDownLatch settled = new CountDownLatch(1);
        volatile StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
            settled.countDown();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            settled.countDown();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        categories.deleteAll();
        items.deleteAll();
        orders.deleteAll();
        bills.deleteAll();
        payments.deleteAll();
        paymentSettings.deleteAll();
        keyCounter = 0;
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("rt-hotel", "RT Hotel", "x", null, "Asia/Kolkata", "INR", now)).getId();
        otherHotelId = hotels.save(new Hotel("rt-other", "Other Hotel", "x", null, "Asia/Kolkata", "INR", now)).getId();
        member("owner@example.com", hotelId, HotelRole.OWNER);
        member("manager@example.com", hotelId, HotelRole.MANAGER);
        member("cook@example.com", hotelId, HotelRole.KITCHEN);
        member("waiter@example.com", hotelId, HotelRole.WAITER);
        member("cashier@example.com", hotelId, HotelRole.CASHIER);
        member("othercook@example.com", otherHotelId, HotelRole.KITCHEN);

        tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-rt"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        soupId = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();

        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-rt\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        guest = matcher.group(1);
        sessionId = sessions.findAll().get(0).getId();

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setTaskScheduler(scheduler);
    }

    @AfterEach
    void stop() {
        client.stop();
        scheduler.shutdown();
    }

    // ---------- helpers ----------

    private void member(String email, String hotel, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), email);
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
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    /** Listens on a channel and returns once the subscription is live (a test message has come back). */
    @SuppressWarnings("unchecked")
    private Listener listen(String staffEmail, String guestCookie, String destination) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (guestCookie != null) {
            handshake.add(HttpHeaders.COOKIE, "qr_customer=" + guestCookie);
        }
        StompHeaders stomp = new StompHeaders();
        if (staffEmail != null) {
            stomp.add("Authorization", "Bearer " + login(staffEmail));
        }
        Probe probe = new Probe();
        client.connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, stomp, probe);
        assertThat(probe.settled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(probe.session).as("connected").isNotNull();

        Listener listener = new Listener();
        probe.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                listener.received.add((Map<String, Object>) payload);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (!listener.saw(m -> "PING".equals(m.get("type"))) && System.currentTimeMillis() < deadline) {
            broker.convertAndSend(destination, Map.of("type", "PING"));
            Thread.sleep(200);
        }
        assertThat(listener.saw(m -> "PING".equals(m.get("type")))).as("subscription live").isTrue();
        return listener;
    }

    private String place() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(soupId))
                        .cookie(new Cookie("qr_customer", guest)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void kitchenStep(String orderId, String action) throws Exception {
        call(post("/api/v1/hotels/" + hotelId + "/kitchen/orders/" + orderId + "/" + action),
                "manager@example.com", null).andExpect(status().isOk());
    }

    private void pay(long amount) throws Exception {
        call(post("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/payments")
                        .header("Idempotency-Key", "events-pay-key-%04d".formatted(++keyCounter)),
                "cashier@example.com", "{\"method\":\"CASH\",\"amountMinor\":%d}".formatted(amount))
                .andExpect(status().isCreated());
    }

    // ---------- tests ----------

    @Test
    void eachChannelHearsOnlyWhatIsForIt() throws Exception {
        Listener kitchen = listen("cook@example.com", null, Topics.kitchen(hotelId));
        Listener waiters = listen("waiter@example.com", null, Topics.waiters(hotelId));
        Listener billing = listen("cashier@example.com", null, Topics.billing(hotelId));
        Listener table = listen(null, guest, Topics.table(sessionId));
        Listener otherKitchen = listen("othercook@example.com", null, Topics.kitchen(otherHotelId));

        String orderId = place();
        assertThat(kitchen.await("ORDER_PLACED", "PENDING")).isNotNull();
        assertThat(table.await("ORDER_PLACED", "PENDING")).isNotNull();
        assertThat(billing.await("ORDER_PLACED", "PENDING")).isNotNull();

        kitchenStep(orderId, "accept");
        assertThat(kitchen.await("ORDER_CHANGED", "ACCEPTED")).isNotNull();
        assertThat(table.await("ORDER_CHANGED", "ACCEPTED")).isNotNull();
        kitchenStep(orderId, "start");
        kitchenStep(orderId, "ready");
        Map<String, Object> ready = waiters.await("ORDER_CHANGED", "READY");
        assertThat(ready).isNotNull();
        assertThat(ready.get("orderId")).isEqualTo(orderId);
        assertThat(ready.get("from")).isEqualTo("PREPARING");
        assertThat(ready.get("tableNumber")).isEqualTo("T1");
        assertThat(ready.get("version")).isNotNull();

        kitchenStep(orderId, "deliver");
        assertThat(waiters.await("ORDER_CHANGED", "DELIVERED")).isNotNull();
        assertThat(billing.await("ORDER_CHANGED", "DELIVERED")).isNotNull();
        assertThat(table.await("ORDER_CHANGED", "DELIVERED")).isNotNull();

        pay(15000);
        assertThat(billing.await("BILL_CHANGED", null)).isNotNull();
        assertThat(table.await("BILL_CHANGED", null)).isNotNull();

        // what must NOT have been delivered
        assertThat(waiters.saw(m -> "ORDER_PLACED".equals(m.get("type")))).isFalse();
        assertThat(waiters.saw(m -> "ACCEPTED".equals(m.get("status")))).isFalse();
        assertThat(billing.saw(m -> "ACCEPTED".equals(m.get("status")) || "READY".equals(m.get("status")))).isFalse();
        assertThat(kitchen.saw(m -> "BILL_CHANGED".equals(m.get("type")))).isFalse();
        assertThat(waiters.saw(m -> "BILL_CHANGED".equals(m.get("type")))).isFalse();
        assertThat(otherKitchen.real()).as("another hotel hears nothing").isEmpty();
    }

    @Test
    void anOrderHeldForPaymentIsHiddenFromTheKitchenUntilItIsReleased() throws Exception {
        call(put("/api/v1/hotels/" + hotelId + "/payments/settings"), "owner@example.com",
                "{\"enabledMethods\":[\"CASH\"],\"partialPaymentsAllowed\":true,\"paymentTiming\":\"BEFORE_FOOD\"}")
                .andExpect(status().isOk());
        Listener kitchen = listen("cook@example.com", null, Topics.kitchen(hotelId));
        Listener billing = listen("cashier@example.com", null, Topics.billing(hotelId));

        place();
        assertThat(billing.await("ORDER_PLACED", "AWAITING_PAYMENT")).as("cashier sees the held order").isNotNull();
        assertThat(kitchen.real()).as("the kitchen does not").isEmpty();

        pay(15000);
        Map<String, Object> released = kitchen.await("ORDER_CHANGED", "PENDING");
        assertThat(released).as("released to the kitchen").isNotNull();
        assertThat(released.get("from")).isEqualTo("AWAITING_PAYMENT");
        assertThat(kitchen.saw(m -> "ORDER_PLACED".equals(m.get("type")))).isFalse();
        assertThat(billing.await("BILL_CHANGED", null)).isNotNull();
    }

    @Test
    void clearingTheTableTellsTheTableTheWaitersAndTheCashier() throws Exception {
        Listener waiters = listen("waiter@example.com", null, Topics.waiters(hotelId));
        Listener billing = listen("cashier@example.com", null, Topics.billing(hotelId));
        Listener table = listen(null, guest, Topics.table(sessionId));

        String orderId = place();
        kitchenStep(orderId, "accept");
        kitchenStep(orderId, "start");
        kitchenStep(orderId, "ready");
        kitchenStep(orderId, "deliver");
        call(post("/api/v1/hotels/" + hotelId + "/waiter/tables/" + tableId + "/clear"), "manager@example.com", null)
                .andExpect(status().isOk());

        assertThat(table.await("SESSION_CHANGED", "CLOSED")).isNotNull();
        assertThat(waiters.await("SESSION_CHANGED", "CLOSED")).isNotNull();
        assertThat(billing.await("SESSION_CHANGED", "CLOSED")).isNotNull();
        assertThat(waiters.await("FLOOR_CHANGED", null)).as("the floor audit entry").isNotNull();
    }

    @Test
    void floorEventsGoToWaitersOnly() throws Exception {
        Listener waiters = listen("waiter@example.com", null, Topics.waiters(hotelId));
        Listener kitchen = listen("cook@example.com", null, Topics.kitchen(hotelId));

        publisher.publishEvent(new FloorChanged(hotelId, "HELP_REQUESTED", tableId, "T1", null));

        Map<String, Object> event = waiters.await("FLOOR_CHANGED", null);
        assertThat(event).isNotNull();
        assertThat(event.get("detail")).isEqualTo("HELP_REQUESTED");
        Thread.sleep(500);
        assertThat(kitchen.real()).isEmpty();
    }
}