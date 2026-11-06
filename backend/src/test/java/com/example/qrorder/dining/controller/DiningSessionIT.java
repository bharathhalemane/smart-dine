package com.example.qrorder.dining.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.dining.service.DiningSessionService;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.model.QrStatus;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class DiningSessionIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired DiningSessionService sessionService;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String t1;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        t1 = seedTable(hotelId, "T1", "Terrace", "tok-t1");
    }

    /** Saves a table and an ACTIVE QR credential whose plain token is the given string. */
    private String seedTable(String hotel, String number, String section, String plainToken) {
        String id = tables.save(new DiningTable(hotel, number, 4, section, Instant.now())).getId();
        credentials.save(new QrCredential(hotel, id, Tokens.sha256(plainToken), "u", Instant.now()));
        return id;
    }

    private void seedMember(String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** Public call: no Authorization header. */
    private ResultActions scan(String token) throws Exception {
        return mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    @Test
    void scanningOpensASessionWithoutLoggingIn() throws Exception {
        scan("tok-t1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelName").value("Spice Garden"))
                .andExpect(jsonPath("$.tableNumber").value("T1"))
                .andExpect(jsonPath("$.section").value("Terrace"))
                .andExpect(jsonPath("$.sessionStatus").value("OPEN"))
                .andExpect(jsonPath("$.newSession").value(true))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());

        DiningSession saved = sessions.findAll().get(0);
        assertThat(saved.getHotelId()).isEqualTo(hotelId);
        assertThat(saved.getTableId()).isEqualTo(t1);
        assertThat(saved.getTableNumber()).isEqualTo("T1");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void aSecondScanOfTheSameTableJoinsTheSameSession() throws Exception {
        scan("tok-t1").andExpect(jsonPath("$.newSession").value(true));
        scan("tok-t1").andExpect(status().isOk()).andExpect(jsonPath("$.newSession").value(false));

        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void differentTablesGetTheirOwnSessions() throws Exception {
        seedTable(hotelId, "T2", null, "tok-t2");

        scan("tok-t1").andExpect(jsonPath("$.newSession").value(true));
        scan("tok-t2").andExpect(jsonPath("$.newSession").value(true));

        assertThat(sessions.count()).isEqualTo(2);
    }

    @Test
    void everyKindOfBadScanLooksTheSameAndOpensNothing() throws Exception {
        // unknown token
        scan("nope").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // revoked token
        QrCredential credential = credentials.findAll().get(0);
        credential.revoke("u", Instant.now());
        credentials.save(credential);
        scan("tok-t1").andExpect(status().isNotFound());

        // valid token, but the table is deactivated
        String off = seedTable(hotelId, "T9", null, "tok-off");
        DiningTable offTable = tables.findById(off).orElseThrow();
        offTable.setActive(false);
        tables.save(offTable);
        scan("tok-off").andExpect(status().isNotFound());

        // valid token, but the hotel is suspended
        seedTable(otherHotelId, "T1", null, "tok-other");
        Hotel other = hotels.findById(otherHotelId).orElseThrow();
        ReflectionTestUtils.setField(other, "status", HotelStatus.SUSPENDED);
        hotels.save(other);
        scan("tok-other").andExpect(status().isNotFound());

        // blank token is a validation error, not a lookup
        scan("").andExpect(status().isBadRequest());

        assertThat(sessions.count()).isZero();
    }

    @Test
    void afterASessionIsClosedTheNextScanOpensANewOne() throws Exception {
        scan("tok-t1").andExpect(jsonPath("$.newSession").value(true));
        DiningSession first = sessions.findAll().get(0);
        first.close(Instant.now());
        sessions.save(first);

        scan("tok-t1").andExpect(jsonPath("$.newSession").value(true));

        assertThat(sessions.count()).isEqualTo(2);
        assertThat(sessions.findAll().stream().filter(DiningSession::isActive)).hasSize(1);
    }

    @Test
    void sixteenSimultaneousScansCreateExactlyOneSession() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = IntStream.range(0, 16)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return sessionService.openOrJoin(hotelId, t1, "T1").opened();
                    }))
                    .toList();
            start.countDown();

            long opened = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    opened++;
                }
            }
            assertThat(opened).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesASecondActiveSessionForATable() {
        sessions.save(new DiningSession(hotelId, t1, "T1", Instant.now()));

        assertThatThrownBy(() -> sessions.save(new DiningSession(hotelId, t1, "T1", Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);

        DiningSession closed = new DiningSession(hotelId, t1, "T1", Instant.now());
        closed.close(Instant.now());
        sessions.save(closed); // closed sessions do not count against the limit
        assertThat(sessions.count()).isEqualTo(2);
    }

    @Test
    void staffSeeOnlyTheirOwnHotelsActiveSessions() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);
        String t2 = seedTable(hotelId, "T2", null, "tok-t2");
        String foreign = seedTable(otherHotelId, "T1", null, "tok-foreign");
        sessions.save(new DiningSession(hotelId, t1, "T1", Instant.now().minusSeconds(60)));
        sessions.save(new DiningSession(hotelId, t2, "T2", Instant.now()));
        DiningSession closed = new DiningSession(hotelId, t2, "T2", Instant.now().minusSeconds(3600));
        closed.close(Instant.now().minusSeconds(1800));
        sessions.save(closed);
        sessions.save(new DiningSession(otherHotelId, foreign, "T1", Instant.now()));

        call(get("/api/v1/hotels/" + hotelId + "/sessions"), login("waiter@example.com"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tableNumber").value("T1")) // oldest first
                .andExpect(jsonPath("$[1].tableNumber").value("T2"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void aTableWithAnOpenSessionCannotBeDeactivated() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String manager = login("manager@example.com");
        scan("tok-t1").andExpect(jsonPath("$.newSession").value(true));

        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + t1), manager, "{\"active\":false}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        call(delete("/api/v1/hotels/" + hotelId + "/tables/" + t1), manager, null)
                .andExpect(status().isConflict());

        assertThat(tables.findById(t1).orElseThrow().isActive()).isTrue();
        assertThat(credentials.findAll().get(0).getStatus()).isEqualTo(QrStatus.ACTIVE);

        DiningSession open = sessions.findByTableIdAndActiveTrue(t1).orElseThrow();
        open.close(Instant.now());
        sessions.save(open);

        call(delete("/api/v1/hotels/" + hotelId + "/tables/" + t1), manager, null)
                .andExpect(status().isNoContent());
    }
}