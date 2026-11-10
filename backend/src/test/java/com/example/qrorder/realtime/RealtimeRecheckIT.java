package com.example.qrorder.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
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
import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.realtime.recheck-seconds=1")
class RealtimeRecheckIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired SimpMessagingTemplate broker;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;
    @Autowired PasswordEncoder encoder;

    WebSocketStompClient client;
    ThreadPoolTaskScheduler scheduler;
    String hotelId;
    String sessionId;
    String guest;
    HotelMembership cookMembership;

    static final class Probe extends StompSessionHandlerAdapter {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        volatile StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
            connected.countDown();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            closed.countDown(); // the server (or network) ended the connection
            connected.countDown();
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

        hotelId = hotels.save(new Hotel("rc-hotel", "RC Hotel", "x", null, "Asia/Kolkata", "INR", now)).getId();
        cookMembership = member("cook@example.com", HotelRole.KITCHEN);
        member("waiter@example.com", HotelRole.WAITER);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-rc"), "u", now));
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-rc\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        matcher.find();
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

    private HotelMembership member(String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), email);
        user.setEmailVerified(true);
        user = users.save(user);
        return memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** Connects, subscribes, and returns once messages on that destination arrive. */
    @SuppressWarnings("unchecked")
    private Probe listen(String staffEmail, String guestCookie, String destination) throws Exception {
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
        assertThat(probe.connected.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(probe.session).as("connected").isNotNull();
        probe.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                probe.received.add((Map<String, Object>) payload);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (probe.received.isEmpty() && System.currentTimeMillis() < deadline) {
            broker.convertAndSend(destination, Map.of("type", "PING"));
            Thread.sleep(200);
        }
        assertThat(probe.received).as("subscription live").isNotEmpty();
        return probe;
    }

    @Test
    void removedStaffAreCutOffWhileOthersStayConnected() throws Exception {
        Probe cook = listen("cook@example.com", null, Topics.kitchen(hotelId));
        Probe waiter = listen("waiter@example.com", null, Topics.waiters(hotelId));

        cookMembership.setStatus(MembershipStatus.REMOVED);
        memberships.save(cookMembership);

        assertThat(cook.closed.await(10, TimeUnit.SECONDS)).as("removed cook is disconnected").isTrue();
        Thread.sleep(2500); // several more re-checks
        assertThat(waiter.closed.getCount()).as("the waiter is left alone").isEqualTo(1);
        assertThat(waiter.session.isConnected()).isTrue();
    }

    @Test
    void aGuestIsCutOffOnceTheTableSessionHasEnded() throws Exception {
        Probe table = listen(null, guest, Topics.table(sessionId));

        mongo.updateFirst(new Query(), new Update().set("active", false), DiningSession.class);

        assertThat(table.closed.await(10, TimeUnit.SECONDS)).as("guest of an ended session is disconnected").isTrue();
    }
}