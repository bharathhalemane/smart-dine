package com.example.qrorder.dining.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.dining.service.DiningSessionService;
import com.example.qrorder.dining.service.SessionLifecycleService;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SessionClosingIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired DiningSessionRepository sessions;
    @Autowired SessionLifecycleService lifecycle;
    @Autowired DiningSessionService sessionService;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        tables.deleteAll();
        sessions.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
    }

    /** Returns the new user's id. */
    private String seedMember(String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
        return user.getId();
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String seedTable(String hotel, String number) {
        return tables.save(new DiningTable(hotel, number, 4, null, Instant.now())).getId();
    }

    private String seedSession(String hotel, String tableId, String number) {
        return sessions.save(new DiningSession(hotel, tableId, number, Instant.now())).getId();
    }

    private ResultActions act(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private String url(String sessionId, String action) {
        return "/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/" + action;
    }

    @Test
    void aManagerClearsTheTableAndTheNextScanGetsAFreshSession() throws Exception {
        String managerId = seedMember("manager@example.com", HotelRole.MANAGER);
        String tableId = seedTable(hotelId, "T1");
        String sessionId = seedSession(hotelId, tableId, "T1");
        String token = login("manager@example.com");

        act(post(url(sessionId, "close")), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists())
                .andExpect(jsonPath("$.history[0].from").value("OPEN"))
                .andExpect(jsonPath("$.history[0].to").value("CLOSED"))
                .andExpect(jsonPath("$.history[0].byUserId").value(managerId));

        DiningSession closed = sessions.findById(sessionId).orElseThrow();
        assertThat(closed.isActive()).isFalse();
        assertThat(closed.getStatus()).isEqualTo(SessionStatus.CLOSED);

        act(get("/api/v1/hotels/" + hotelId + "/floor"), token)
                .andExpect(jsonPath("$[0].state").value("FREE"))
                .andExpect(jsonPath("$[0].sessionId").doesNotExist());
        act(get("/api/v1/hotels/" + hotelId + "/sessions"), token).andExpect(jsonPath("$.length()").value(0));

        DiningSessionService.OpenResult next = sessionService.openOrJoin(hotelId, tableId, "T1");
        assertThat(next.opened()).isTrue();
        assertThat(next.session().getId()).isNotEqualTo(sessionId);
    }

    @Test
    void closingFromBillRequestedRecordsWhereItCameFrom() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String sessionId = seedSession(hotelId, seedTable(hotelId, "T1"), "T1");
        String token = login("manager@example.com");

        act(post(url(sessionId, "request-bill")), token).andExpect(status().isOk());
        act(post(url(sessionId, "close")), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[1].from").value("BILL_REQUESTED"))
                .andExpect(jsonPath("$.history[1].to").value("CLOSED"));
    }

    @Test
    void waitersMayClearOnlyWhenTheHotelAllowsItAndOtherRolesNever() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);
        seedMember("cashier@example.com", HotelRole.CASHIER);
        seedMember("cook@example.com", HotelRole.KITCHEN);
        String sessionId = seedSession(hotelId, seedTable(hotelId, "T1"), "T1");
        String waiter = login("waiter@example.com");

        act(post(url(sessionId, "close")), waiter)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Hotel hotel = hotels.findById(hotelId).orElseThrow();
        hotel.setWaitersMayClearTables(true);
        hotel.setCashiersMayAdjustBills(true); // an unrelated switch must not grant clearing to cashiers
        hotels.save(hotel);

        act(post(url(sessionId, "close")), login("cashier@example.com")).andExpect(status().isForbidden());
        act(post(url(sessionId, "close")), login("cook@example.com")).andExpect(status().isForbidden());
        assertThat(sessions.findById(sessionId).orElseThrow().isActive()).isTrue();

        // same JWT as before: the new permission comes from the database
        act(post(url(sessionId, "close")), waiter)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void aClosedSessionStaysClosed() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String sessionId = seedSession(hotelId, seedTable(hotelId, "T1"), "T1");
        String token = login("manager@example.com");

        act(post(url(sessionId, "close")), token).andExpect(status().isOk());

        act(post(url(sessionId, "close")), token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        act(post(url(sessionId, "reopen")), token).andExpect(status().isConflict());
        act(post(url(sessionId, "request-bill")), token).andExpect(status().isConflict());
        assertThat(sessions.findById(sessionId).orElseThrow().getStatusHistory()).hasSize(1);
    }

    @Test
    void aSessionFromAnotherHotelIsNotFoundAndStaysOpen() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String foreign = seedSession(otherHotelId, seedTable(otherHotelId, "T1"), "T1");

        act(post(url(foreign, "close")), login("manager@example.com")).andExpect(status().isNotFound());

        assertThat(sessions.findById(foreign).orElseThrow().isActive()).isTrue();
    }

    @Test
    void tenSimultaneousClearsSucceedExactlyOnce() throws Exception {
        String managerId = seedMember("manager@example.com", HotelRole.MANAGER);
        String sessionId = seedSession(hotelId, seedTable(hotelId, "T1"), "T1");
        TenantContext manager = new TenantContext(hotelId, managerId, Set.of(HotelRole.MANAGER),
                Set.of(Permission.CLEAR_TABLE));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        int successes = 0;
        List<ErrorCode> failures = new ArrayList<>();
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    lifecycle.close(manager, sessionId);
                    return true;
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                try {
                    if (result.get(30, TimeUnit.SECONDS)) {
                        successes++;
                    }
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(DomainException.class);
                    failures.add(((DomainException) e.getCause()).code());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes).isEqualTo(1);
        assertThat(failures).hasSize(9).containsOnly(ErrorCode.INVALID_STATE_TRANSITION);
        DiningSession saved = sessions.findById(sessionId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(saved.getStatusHistory()).hasSize(1);
    }

    @Test
    void closingAndScanningAtTheSameMomentNeverLeavesTwoActiveSessions() throws Exception {
        String managerId = seedMember("manager@example.com", HotelRole.MANAGER);
        String tableId = seedTable(hotelId, "T1");
        TenantContext manager = new TenantContext(hotelId, managerId, Set.of(HotelRole.MANAGER),
                Set.of(Permission.CLEAR_TABLE));
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            for (int round = 0; round < 25; round++) {
                sessions.deleteAll();
                String oldId = seedSession(hotelId, tableId, "T1");
                CountDownLatch start = new CountDownLatch(1);

                Future<?> closing = pool.submit(() -> {
                    start.await();
                    lifecycle.close(manager, oldId);
                    return null;
                });
                Future<Boolean> scanning = pool.submit(() -> {
                    start.await();
                    return sessionService.openOrJoin(hotelId, tableId, "T1").opened();
                });
                start.countDown();
                closing.get(30, TimeUnit.SECONDS);
                boolean openedNew = scanning.get(30, TimeUnit.SECONDS);

                List<DiningSession> active = sessions.findByHotelIdAndActiveTrue(hotelId);
                assertThat(sessions.findById(oldId).orElseThrow().getStatus()).isEqualTo(SessionStatus.CLOSED);
                if (openedNew) {
                    // the scan came after the clear: exactly one fresh session, and it is not the old one
                    assertThat(active).hasSize(1);
                    assertThat(active.get(0).getId()).isNotEqualTo(oldId);
                } else {
                    // the scan joined the old session just before it was cleared: nothing is left active
                    assertThat(active).isEmpty();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}