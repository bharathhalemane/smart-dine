package com.example.qrorder.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.identity.service.JwtService;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompSubscriptionIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    enum Outcome { DELIVERED, REFUSED, NOTHING }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SimpMessagingTemplate broker;
    @Autowired UserRepository users;
    @Autowired JwtService jwtService;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;

    WebSocketStompClient client;
    ThreadPoolTaskScheduler scheduler;
    String hotelA;
    String hotelB;
    String ownerToken;
    String kitchenToken;
    String waiterToken;
    String cashierToken;
    String outsiderToken; // kitchen staff of hotel B
    String removedToken;  // kitchen staff of hotel A whose membership was removed
    String guestOne;      // cookie, table T1
    String sessionOne;
    String sessionTwo;

    static final class Probe extends StompSessionHandlerAdapter {
        final CountDownLatch settled = new CountDownLatch(1);
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
            settled.countDown();
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add("frame");
            settled.countDown();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add("transport");
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
        Instant now = Instant.now();

        hotelA = hotels.save(new Hotel("sub-hotel-a", "Hotel A", "x", null, "Asia/Kolkata", "INR", now)).getId();
        hotelB = hotels.save(new Hotel("sub-hotel-b", "Hotel B", "x", null, "Asia/Kolkata", "INR", now)).getId();

        ownerToken = staff("owner@example.com", hotelA, HotelRole.OWNER, MembershipStatus.ACTIVE);
        kitchenToken = staff("kitchen@example.com", hotelA, HotelRole.KITCHEN, MembershipStatus.ACTIVE);
        waiterToken = staff("waiter@example.com", hotelA, HotelRole.WAITER, MembershipStatus.ACTIVE);
        cashierToken = staff("cashier@example.com", hotelA, HotelRole.CASHIER, MembershipStatus.ACTIVE);
        outsiderToken = staff("outsider@example.com", hotelB, HotelRole.KITCHEN, MembershipStatus.ACTIVE);
        removedToken = staff("removed@example.com", hotelA, HotelRole.KITCHEN, MembershipStatus.REMOVED);

        for (String[] t : new String[][] {{"T1", "tok-sub-1"}, {"T2", "tok-sub-2"}}) {
            String tableId = tables.save(new DiningTable(hotelA, t[0], 4, null, now)).getId();
            credentials.save(new QrCredential(hotelA, tableId, Tokens.sha256(t[1]), "u", now));
        }
        guestOne = scan("tok-sub-1");
        scan("tok-sub-2");
        for (DiningSession session : sessions.findAll()) {
            if ("T1".equals(session.getTableNumber())) {
                sessionOne = session.getId();
            } else {
                sessionTwo = session.getId();
            }
        }

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

    private String staff(String email, String hotelId, HotelRole role, MembershipStatus status) {
        User user = new User(email, "x-not-used", email);
        user.setEmailVerified(true);
        user = users.save(user);
        HotelMembership membership = new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now());
        membership.setStatus(status);
        memberships.save(membership);
        return jwtService.issueAccessToken(user);
    }

    private String scan(String token) {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        String setCookie = rest.postForEntity("/api/v1/scan", new HttpEntity<>(Map.of("token", token), json),
                String.class).getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** Connects fresh, subscribes, and reports whether messages arrive or the server refuses. */
    private Outcome tryListen(String staffToken, String guestCookie, String destination) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (guestCookie != null) {
            handshake.add(HttpHeaders.COOKIE, "qr_customer=" + guestCookie);
        }
        StompHeaders stomp = new StompHeaders();
        if (staffToken != null) {
            stomp.add("Authorization", "Bearer " + staffToken);
        }
        Probe probe = new Probe();
        client.connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, stomp, probe);
        assertThat(probe.settled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(probe.session).as("connected").isNotNull();

        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        probe.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add(payload);
            }
        });

        Outcome outcome = Outcome.NOTHING;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            broker.convertAndSend(destination, Map.of("ping", 1)); // what 11.3 will do for real
            if (inbox.poll(300, TimeUnit.MILLISECONDS) != null) {
                outcome = Outcome.DELIVERED;
                break;
            }
            if (!probe.errors.isEmpty()) {
                outcome = Outcome.REFUSED;
                break;
            }
        }
        try {
            probe.session.disconnect();
        } catch (RuntimeException alreadyClosed) {
            // the server closes the connection when it refuses a subscription
        }
        return outcome;
    }

    private String kitchen(String hotelId) { return Topics.kitchen(hotelId); }

    // ---------- staff channels ----------

    @Test
    void kitchenStaffHearTheKitchenChannelAndNothingElse() throws Exception {
        assertThat(tryListen(kitchenToken, null, Topics.kitchen(hotelA))).isEqualTo(Outcome.DELIVERED);
        assertThat(tryListen(kitchenToken, null, Topics.waiters(hotelA))).isEqualTo(Outcome.REFUSED);
        assertThat(tryListen(kitchenToken, null, Topics.billing(hotelA))).isEqualTo(Outcome.REFUSED);
    }

    @Test
    void waitersHearTheWaiterChannelOnly() throws Exception {
        assertThat(tryListen(waiterToken, null, Topics.waiters(hotelA))).isEqualTo(Outcome.DELIVERED);
        assertThat(tryListen(waiterToken, null, Topics.kitchen(hotelA))).isEqualTo(Outcome.REFUSED);
        assertThat(tryListen(waiterToken, null, Topics.billing(hotelA))).isEqualTo(Outcome.REFUSED);
    }

    @Test
    void cashiersHearTheBillingChannelOnly() throws Exception {
        assertThat(tryListen(cashierToken, null, Topics.billing(hotelA))).isEqualTo(Outcome.DELIVERED);
        assertThat(tryListen(cashierToken, null, Topics.kitchen(hotelA))).isEqualTo(Outcome.REFUSED);
        assertThat(tryListen(cashierToken, null, Topics.waiters(hotelA))).isEqualTo(Outcome.REFUSED);
    }

    @Test
    void theOwnerHearsAllThreeStaffChannelsOfTheirOwnHotel() throws Exception {
        assertThat(tryListen(ownerToken, null, Topics.kitchen(hotelA))).isEqualTo(Outcome.DELIVERED);
        assertThat(tryListen(ownerToken, null, Topics.waiters(hotelA))).isEqualTo(Outcome.DELIVERED);
        assertThat(tryListen(ownerToken, null, Topics.billing(hotelA))).isEqualTo(Outcome.DELIVERED);
        assertThat(tryListen(ownerToken, null, Topics.kitchen(hotelB))).isEqualTo(Outcome.REFUSED);
    }

    @Test
    void staffOfAnotherHotelAndRemovedStaffAreRefused() throws Exception {
        assertThat(tryListen(outsiderToken, null, Topics.kitchen(hotelA))).isEqualTo(Outcome.REFUSED);
        assertThat(tryListen(outsiderToken, null, Topics.kitchen(hotelB))).isEqualTo(Outcome.DELIVERED); // their own
        assertThat(tryListen(removedToken, null, Topics.kitchen(hotelA))).isEqualTo(Outcome.REFUSED);
    }

    // ---------- guests ----------

    @Test
    void aGuestHearsOnlyTheirOwnTableAndNoStaffChannel() throws Exception {
        assertThat(tryListen(null, guestOne, Topics.table(sessionOne))).isEqualTo(Outcome.DELIVERED);
        assertThat(tryListen(null, guestOne, Topics.table(sessionTwo))).isEqualTo(Outcome.REFUSED);
        assertThat(tryListen(null, guestOne, Topics.kitchen(hotelA))).isEqualTo(Outcome.REFUSED);
    }

    @Test
    void staffCannotListenToATableChannel() throws Exception {
        assertThat(tryListen(ownerToken, null, Topics.table(sessionOne))).isEqualTo(Outcome.REFUSED);
    }

    // ---------- patterns and odd destinations ----------

    @Test
    void wildcardsAndOddDestinationsAreRefused() throws Exception {
        for (String destination : List.of(
                "/topic/hotels/*/kitchen",
                "/topic/hotels/**",
                "/topic/**",
                "/topic/hotels/" + hotelA + "/*",
                "/topic/hotels/" + hotelA + "/kitchen/extra",
                "/topic/hotels/" + hotelA,
                "/topic/tables/*",
                "/topic/tables/" + sessionOne + "/more",
                "/queue/anything",
                "/user/queue/a/b")) {
            assertThat(tryListen(ownerToken, null, destination))
                    .as("subscribing to " + destination).isEqualTo(Outcome.REFUSED);
        }
    }
}