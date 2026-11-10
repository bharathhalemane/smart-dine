package com.example.qrorder.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.realtime.Topics;
import com.example.qrorder.realtime.WebSocketConfig;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
        properties = "app.realtime.recheck-seconds=3600")
class HotelSuspendRealtimeIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired SimpMessagingTemplate broker;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired PasswordEncoder encoder;

    WebSocketStompClient client;
    ThreadPoolTaskScheduler scheduler;
    String hotelId;

    static final class Probe extends StompSessionHandlerAdapter {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final List<Object> received = new CopyOnWriteArrayList<>();
        volatile StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
            connected.countDown();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            closed.countDown();
            connected.countDown();
        }
    }

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        Instant now = Instant.now();

        User admin = new User("admin@example.com", encoder.encode(PASSWORD), "Admin");
        admin.setEmailVerified(true);
        admin.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        users.save(admin);
        User cook = new User("cook@example.com", encoder.encode(PASSWORD), "Cook");
        cook.setEmailVerified(true);
        cook = users.save(cook);

        hotelId = hotels.save(new Hotel("live-hotel", "Live Hotel", cook.getId(), null, "Asia/Kolkata", "INR", now))
                .getId();
        memberships.save(new HotelMembership(hotelId, cook.getId(), Set.of(HotelRole.KITCHEN), cook.getId(), now));

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

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void suspendingAHotelDisconnectsItsStaffAtOnce() throws Exception {
        StompHeaders stomp = new StompHeaders();
        stomp.add("Authorization", "Bearer " + login("cook@example.com"));
        Probe probe = new Probe();
        client.connectAsync("ws://localhost:" + port + WebSocketConfig.ENDPOINT, new WebSocketHttpHeaders(),
                stomp, probe);
        assertThat(probe.connected.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(probe.session).isNotNull();
        String destination = Topics.kitchen(hotelId);
        probe.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                probe.received.add(payload);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (probe.received.isEmpty() && System.currentTimeMillis() < deadline) {
            broker.convertAndSend(destination, Map.of("type", "PING"));
            Thread.sleep(200);
        }
        assertThat(probe.received).as("subscription live").isNotEmpty();
        assertThat(probe.closed.getCount()).isEqualTo(1);

        mvc.perform(post("/api/v1/admin/hotels/" + hotelId + "/suspend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cutting the line\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("admin@example.com")))
                .andExpect(status().isOk());

        assertThat(probe.closed.await(5, TimeUnit.SECONDS)).as("cook is disconnected right away").isTrue();
    }
}