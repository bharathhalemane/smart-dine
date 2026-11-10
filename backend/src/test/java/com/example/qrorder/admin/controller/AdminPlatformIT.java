package com.example.qrorder.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.admin.repository.AdminAuditRepository;
import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
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

class AdminPlatformIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired AdminAuditRepository audit;
    @Autowired PasswordEncoder encoder;

    String adminToken;
    String ownerToken;

    @BeforeEach
    void seed() throws Exception {
        users.deleteAll();
        hotels.deleteAll();
        audit.deleteAll();
        mongo.getCollection("orders").deleteMany(new Document());
        mongo.getCollection("diningSessions").deleteMany(new Document());
        Instant now = Instant.now();

        User admin = account("admin@example.com");
        admin.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        users.save(admin);
        User owner = users.save(account("owner@example.com"));
        User gone = users.save(account("gone@example.com"));
        mongo.updateFirst(Query.query(Criteria.where("id").is(gone.getId())),
                new Update().set("status", UserStatus.DISABLED), User.class);

        hotels.save(new Hotel("a-new", "New Hotel", owner.getId(), null, "Asia/Kolkata", "INR", now));
        hotels.save(new Hotel("b-mid", "Mid Hotel", owner.getId(), null, "Asia/Kolkata", "INR",
                now.minus(Duration.ofDays(15))));
        Hotel old = hotels.save(new Hotel("c-old", "Old Hotel", owner.getId(), null, "Asia/Kolkata", "INR",
                now.minus(Duration.ofDays(60))));
        Hotel shut = hotels.save(new Hotel("d-shut", "Shut Hotel", owner.getId(), null, "Asia/Kolkata", "INR",
                now.minus(Duration.ofDays(60))));
        setStatus(old.getId(), HotelStatus.SUSPENDED);
        setStatus(shut.getId(), HotelStatus.DEACTIVATED);

        adminToken = login("admin@example.com");
        ownerToken = login("owner@example.com");
    }

    // ---------- helpers ----------

    private User account(String email) {
        User user = new User(email, encoder.encode(PASSWORD), email);
        user.setEmailVerified(true);
        return user;
    }

    private void setStatus(String hotelId, HotelStatus status) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(hotelId)), new Update().set("status", status),
                Hotel.class);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions getAs(String token, String url) throws Exception {
        var request = get(url);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private void order(String hotelId, Instant at) {
        mongo.getCollection("orders").insertOne(new Document("hotelId", hotelId).append("createdAt", Date.from(at)));
    }

    private void session(String hotelId, boolean active) {
        mongo.getCollection("diningSessions").insertOne(new Document("hotelId", hotelId)
                .append("tableId", java.util.UUID.randomUUID().toString()).append("active", active));
    }

    private void entry(String targetId, AdminAuditAction action, String actorId, Instant at, String reason) {
        audit.save(new AdminAuditEntry(at, actorId, "admin@example.com", "Admin", action, "HOTEL", targetId,
                "Label " + targetId, "ACTIVE", "SUSPENDED", reason, null));
    }

    // ---------- security ----------

    @Test
    void allThreeRoutesAreForPlatformAdminsOnly() throws Exception {
        for (String url : new String[] {"/api/v1/admin/stats", "/api/v1/admin/audit", "/api/v1/admin/health"}) {
            getAs(null, url).andExpect(status().isUnauthorized());
            getAs(ownerToken, url).andExpect(status().isForbidden());
            getAs(adminToken, url).andExpect(status().isOk());
        }
    }

    // ---------- stats ----------

    @Test
    void statsCountHotelsUsersAndActivity() throws Exception {
        Instant now = Instant.now();
        order("h1", now.minus(Duration.ofHours(2)));
        order("h1", now.minus(Duration.ofHours(30)));
        order("h2", now.minus(Duration.ofDays(3)));
        order("h2", now.minus(Duration.ofDays(20)));
        session("h1", true);
        session("h1", true);
        session("h2", false);

        getAs(adminToken, "/api/v1/admin/stats").andExpect(status().isOk())
                .andExpect(jsonPath("$.hotels.total").value(4))
                .andExpect(jsonPath("$.hotels.active").value(2))
                .andExpect(jsonPath("$.hotels.suspended").value(1))
                .andExpect(jsonPath("$.hotels.deactivated").value(1))
                .andExpect(jsonPath("$.hotels.createdLast7Days").value(1))
                .andExpect(jsonPath("$.hotels.createdLast30Days").value(2))
                .andExpect(jsonPath("$.users.total").value(3))
                .andExpect(jsonPath("$.users.active").value(2))
                .andExpect(jsonPath("$.users.disabled").value(1))
                .andExpect(jsonPath("$.users.platformAdmins").value(1))
                .andExpect(jsonPath("$.activity.openTableSessions").value(2))
                .andExpect(jsonPath("$.activity.ordersLast24Hours").value(1))
                .andExpect(jsonPath("$.activity.ordersLast7Days").value(3))
                .andExpect(jsonPath("$.activity.hotelsWithOrdersLast7Days").value(2))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    // ---------- audit viewer ----------

    @Test
    void auditIsNewestFirstAndFiltersCombine() throws Exception {
        Instant t = Instant.parse("2026-11-01T10:00:00Z");
        entry("h1", AdminAuditAction.HOTEL_SUSPENDED, "u1", t, "first");
        entry("h1", AdminAuditAction.HOTEL_REACTIVATED, "u1", t.plusSeconds(60), "second");
        entry("h2", AdminAuditAction.HOTEL_SUSPENDED, "u2", t.plusSeconds(120), "third");

        getAs(adminToken, "/api/v1/admin/audit").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].reason").value("third"))
                .andExpect(jsonPath("$.items[2].reason").value("first"));

        getAs(adminToken, "/api/v1/admin/audit?targetId=h1").andExpect(jsonPath("$.totalElements").value(2));
        getAs(adminToken, "/api/v1/admin/audit?actorUserId=u2").andExpect(jsonPath("$.totalElements").value(1));
        getAs(adminToken, "/api/v1/admin/audit?action=HOTEL_SUSPENDED")
                .andExpect(jsonPath("$.totalElements").value(2));
        getAs(adminToken, "/api/v1/admin/audit?targetId=h1&action=HOTEL_SUSPENDED")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("first"));
        getAs(adminToken, "/api/v1/admin/audit?from=2026-11-01T10:01:00Z&to=2026-11-01T10:01:30Z")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("second"));
    }

    @Test
    void auditPagesAndRejectsBadInput() throws Exception {
        Instant t = Instant.parse("2026-11-01T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            entry("h1", AdminAuditAction.HOTEL_SUSPENDED, "u1", t.plusSeconds(i), "r" + i);
        }
        getAs(adminToken, "/api/v1/admin/audit?size=2&page=0").andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.items[0].reason").value("r4"));
        getAs(adminToken, "/api/v1/admin/audit?size=2&page=2").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("r0"));

        getAs(adminToken, "/api/v1/admin/audit?action=HOTEL_EXPLODED").andExpect(status().isBadRequest());
        getAs(adminToken, "/api/v1/admin/audit?size=101").andExpect(status().isBadRequest());
        getAs(adminToken, "/api/v1/admin/audit?size=0").andExpect(status().isBadRequest());
        getAs(adminToken, "/api/v1/admin/audit?page=-1").andExpect(status().isBadRequest());
        getAs(adminToken, "/api/v1/admin/audit?from=2026-12-01T00:00:00Z&to=2026-11-01T00:00:00Z")
                .andExpect(status().isBadRequest());
    }

    @Test
    void anActualSuspensionShowsUpInTheViewer() throws Exception {
        String hotelId = hotels.findAll().stream().filter(h -> h.getSlug().equals("a-new")).findFirst()
                .orElseThrow().getId();
        mvc.perform(post("/api/v1/admin/hotels/" + hotelId + "/suspend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Seen in the viewer\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        getAs(adminToken, "/api/v1/admin/audit?targetId=" + hotelId).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].action").value("HOTEL_SUSPENDED"))
                .andExpect(jsonPath("$.items[0].actorEmail").value("admin@example.com"))
                .andExpect(jsonPath("$.items[0].reason").value("Seen in the viewer"));
    }

    // ---------- health ----------

    @Test
    void healthReportsTheDatabaseAndTheRuntime() throws Exception {
        getAs(adminToken, "/api/v1/admin/health").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.up").value(true))
                .andExpect(jsonPath("$.runtime.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.runtime.springBootVersion").isNotEmpty())
                .andExpect(jsonPath("$.runtime.heapMaxMb").isNumber());
        assertThat(true).isTrue();
    }
}