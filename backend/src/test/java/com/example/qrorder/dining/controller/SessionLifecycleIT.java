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

class SessionLifecycleIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired DiningSessionRepository sessions;
    @Autowired SessionLifecycleService lifecycle;
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

    private String seedSession(String hotel, String number) {
        String tableId = tables.save(new DiningTable(hotel, number, 4, null, Instant.now())).getId();
        return sessions.save(new DiningSession(hotel, tableId, number, Instant.now())).getId();
    }

    private ResultActions act(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private String sessionUrl(String sessionId, String action) {
        return "/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + (action == null ? "" : "/" + action);
    }

    @Test
    void aWaiterRequestsTheBillAndTheTransitionIsRecorded() throws Exception {
        String waiterId = seedMember("waiter@example.com", HotelRole.WAITER);
        String sessionId = seedSession(hotelId, "T1");

        act(post(sessionUrl(sessionId, "request-bill")), login("waiter@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BILL_REQUESTED"))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].from").value("OPEN"))
                .andExpect(jsonPath("$.history[0].to").value("BILL_REQUESTED"))
                .andExpect(jsonPath("$.history[0].byUserId").value(waiterId));

        DiningSession saved = sessions.findById(sessionId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.BILL_REQUESTED);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void reopeningGoesBackToOpenAndKeepsBothHistoryEntries() throws Exception {
        seedMember("cashier@example.com", HotelRole.CASHIER);
        String sessionId = seedSession(hotelId, "T1");
        String token = login("cashier@example.com");

        act(post(sessionUrl(sessionId, "request-bill")), token).andExpect(status().isOk());
        act(post(sessionUrl(sessionId, "reopen")), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[1].from").value("BILL_REQUESTED"))
                .andExpect(jsonPath("$.history[1].to").value("OPEN"));
    }

    @Test
    void movesOutsideTheStateMachineAreRejectedAndChangeNothing() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);
        String sessionId = seedSession(hotelId, "T1");
        String token = login("waiter@example.com");

        // reopen on an OPEN session
        act(post(sessionUrl(sessionId, "reopen")), token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // request the bill twice
        act(post(sessionUrl(sessionId, "request-bill")), token).andExpect(status().isOk());
        act(post(sessionUrl(sessionId, "request-bill")), token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // anything on a closed session
        DiningSession session = sessions.findById(sessionId).orElseThrow();
        session.close(Instant.now());
        sessions.save(session);
        act(post(sessionUrl(sessionId, "request-bill")), token).andExpect(status().isConflict());
        act(post(sessionUrl(sessionId, "reopen")), token).andExpect(status().isConflict());

        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(sessions.findById(sessionId).orElseThrow().getStatusHistory()).hasSize(1); // only the one valid move
    }

    @Test
    void kitchenStaffCannotChangeSessionStateButAnyMemberCanLook() throws Exception {
        seedMember("cook@example.com", HotelRole.KITCHEN);
        String sessionId = seedSession(hotelId, "T1");
        String token = login("cook@example.com");

        act(post(sessionUrl(sessionId, "request-bill")), token)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.OPEN);

        act(get(sessionUrl(sessionId, null)), token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void aSessionFromAnotherHotelIsNotFoundAndStaysUntouched() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);
        String foreignSession = seedSession(otherHotelId, "T1");
        String token = login("waiter@example.com");

        act(post(sessionUrl(foreignSession, "request-bill")), token).andExpect(status().isNotFound());
        act(post(sessionUrl(foreignSession, "reopen")), token).andExpect(status().isNotFound());
        act(get(sessionUrl(foreignSession, null)), token).andExpect(status().isNotFound());

        assertThat(sessions.findById(foreignSession).orElseThrow().getStatus()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void tenSimultaneousBillRequestsSucceedExactlyOnce() throws Exception {
        String waiterId = seedMember("waiter@example.com", HotelRole.WAITER);
        String sessionId = seedSession(hotelId, "T1");
        TenantContext waiter = new TenantContext(hotelId, waiterId, Set.of(HotelRole.WAITER),
                Set.of(Permission.DELIVER_ORDERS));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        int successes = 0;
        List<ErrorCode> failures = new ArrayList<>();
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    lifecycle.requestBill(waiter, sessionId);
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
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.BILL_REQUESTED);
        assertThat(saved.getStatusHistory()).hasSize(1);
    }

    @Test
    void theFloorShowsEveryActiveTableWithItsDerivedState() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);
        String token = login("waiter@example.com");
        tables.save(new DiningTable(hotelId, "T2", 2, "Terrace", Instant.now())); // free
        String openSession = seedSession(hotelId, "T10");                          // will be BILL_REQUESTED
        String occupiedSession = seedSession(hotelId, "T3");                       // OCCUPIED
        DiningTable off = new DiningTable(hotelId, "T99", 2, null, Instant.now());
        off.setActive(false);
        tables.save(off);                                                          // inactive: not listed
        seedSession(otherHotelId, "T1");                                           // other hotel: not listed

        act(post(sessionUrl(openSession, "request-bill")), token).andExpect(status().isOk());

        act(get("/api/v1/hotels/" + hotelId + "/floor"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].number").value("T2"))
                .andExpect(jsonPath("$[0].state").value("FREE"))
                .andExpect(jsonPath("$[0].sessionId").doesNotExist())
                .andExpect(jsonPath("$[1].number").value("T3"))
                .andExpect(jsonPath("$[1].state").value("OCCUPIED"))
                .andExpect(jsonPath("$[1].sessionId").value(occupiedSession))
                .andExpect(jsonPath("$[2].number").value("T10"))
                .andExpect(jsonPath("$[2].state").value("BILL_REQUESTED"))
                .andExpect(jsonPath("$[2].sessionId").value(openSession));
    }
}