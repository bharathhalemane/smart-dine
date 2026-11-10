package com.example.qrorder.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.common.security.Tokens;
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
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompConnectIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired JwtService jwtService;
    @Autowired HotelRepository hotels;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;

    WebSocketStompClient client;
    ThreadPoolTaskScheduler scheduler;
    String staffId;
    String staffToken;
    String guestCookie;

    /** What happened to one connection attempt. */
    static final class Probe extends StompSessionHandlerAdapter {
        final CountDownLatch settled = new CountDownLatch(1);
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile boolean connected;
        volatile StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
            this.connected = true;
            settled.countDown();
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) { // ERROR frames
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
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        Instant now = Instant.now();

        User user = new User("ws-staff@example.com", "x-not-used", "Staff Sam");
        user.setEmailVerified(true);
        user = users.save(user);
        staffId = user.getId();
        staffToken = jwtService.issueAccessToken(user);

        String hotelId = hotels.save(new Hotel("ws-hotel", "WS Hotel", "x", null, "Asia/Kolkata", "INR", now)).getId();
        String tableId = tables.save(new DiningTable(hotelId, "T7", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-ws"), "u", now));

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        String setCookie = rest.postForEntity("/api/v1/scan", new HttpEntity<>(Map.of("token", "tok-ws"), json),
                String.class).getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        guestCookie = matcher.group(1);

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

    private Probe connect(String authorization, String cookie, String origin) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (cookie != null) {
            handshake.add(HttpHeaders.COOKIE, "qr_customer=" + cookie);
        }
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders stomp = new StompHeaders();
        if (authorization != null) {
            stomp.add("Authorization", authorization);
        }
        Probe probe = new Probe();
        client.connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, handshake, stomp, probe)
                .whenComplete((session, error) -> {
                    if (error != null) {
                        probe.errors.add("connect-failed");
                        probe.settled.countDown();
                    }
                });
        assertThat(probe.settled.await(10, TimeUnit.SECONDS)).as("connection attempt settled").isTrue();
        return probe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> whoAmI(StompSession session) throws Exception {
        BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/whoami", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((Map<String, Object>) payload);
            }
        });

        // The subscription is registered a moment after it is sent, so ask until the server answers.
        Map<String, Object> answer = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (answer == null && System.currentTimeMillis() < deadline) {
            session.send("/app/whoami", Map.of());
            answer = inbox.poll(300, TimeUnit.MILLISECONDS);
        }
        assertThat(answer).as("whoami answer").isNotNull();
        return answer;
    }

    private void assertRefused(Probe probe) {
        assertThat(probe.connected).isFalse();
        assertThat(probe.errors).isNotEmpty();
    }

    // ---------- who may connect ----------

    @Test
    void aStaffMemberConnectsWithTheirAccessTokenAndIsRecognised() throws Exception {
        Probe probe = connect("Bearer " + staffToken, null, null);

        assertThat(probe.connected).isTrue();
        Map<String, Object> me = whoAmI(probe.session);
        assertThat(me.get("kind")).isEqualTo("STAFF");
        assertThat(me.get("id")).isEqualTo(staffId);
    }

    @Test
    void aGuestConnectsWithTheTableCookieAndIsRecognised() throws Exception {
        Probe probe = connect(null, guestCookie, null);

        assertThat(probe.connected).isTrue();
        Map<String, Object> me = whoAmI(probe.session);
        assertThat(me.get("kind")).isEqualTo("CUSTOMER");
        assertThat(me.get("tableNumber")).isEqualTo("T7");
        assertThat(me.get("hotelId")).isNotNull();
    }

    @Test
    void aConnectionWithNoCredentialsIsRefused() throws Exception {
        assertRefused(connect(null, null, null));
    }

    @Test
    void aGarbageTokenAndAGarbageCookieAreRefused() throws Exception {
        assertRefused(connect("Bearer not-a-real-token", null, null));
        assertRefused(connect("Token abc", null, null));
        assertRefused(connect(null, "not-a-real-cookie", null));
    }

    @Test
    void anotherWebsiteCannotOpenTheConnection() throws Exception {
        assertRefused(connect(null, guestCookie, "http://evil.example"));
        assertThat(connect(null, guestCookie, "http://localhost:5173").connected).isTrue(); // an allowed origin
    }

    // ---------- what a connected client may do ----------

    @Test
    void aClientCannotSubscribeToABroadcastTopicYet() throws Exception {
        Probe probe = connect("Bearer " + staffToken, null, null);
        assertThat(probe.connected).isTrue();
        Probe.class.cast(probe).errors.clear();

        probe.session.subscribe("/topic/hotel/anything/kitchen", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) { }
        });

        long deadline = System.currentTimeMillis() + 5000;
        while (probe.errors.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(probe.errors).isNotEmpty();
    }

    @Test
    void aClientCannotBroadcastToATopic() throws Exception {
        Probe probe = connect(null, guestCookie, null);
        assertThat(probe.connected).isTrue();
        probe.errors.clear();

        probe.session.send("/topic/hotel/anything/kitchen", Map.of("spam", true));

        long deadline = System.currentTimeMillis() + 5000;
        while (probe.errors.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(probe.errors).isNotEmpty();
    }
}