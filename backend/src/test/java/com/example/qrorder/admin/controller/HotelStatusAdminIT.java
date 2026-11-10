package com.example.qrorder.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.admin.repository.AdminAuditRepository;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.PlatformRole;
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
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

class HotelStatusAdminIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;
    @Autowired AdminAuditRepository audit;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String adminId;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        audit.deleteAll();
        Instant now = Instant.now();

        adminId = users.save(admin("admin@example.com", "Platform Admin")).getId();
        users.save(admin("admin2@example.com", "Second Admin"));
        User owner = users.save(plain("owner@example.com", "Owner Om"));

        hotelId = hotels.save(new Hotel("status-hotel", "Status Hotel", owner.getId(), "MG Road", "Asia/Kolkata",
                "INR", now)).getId();
        memberships.save(new HotelMembership(hotelId, owner.getId(), Set.of(HotelRole.OWNER), owner.getId(), now));
        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-status"), "u", now));
    }

    // ---------- helpers ----------

    private User admin(String email, String name) {
        User user = plain(email, name);
        user.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        return user;
    }

    private User plain(String email, String name) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        return user;
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions act(String action, String email, String json) throws Exception {
        var request = post("/api/v1/admin/hotels/" + hotelId + "/" + action)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        return mvc.perform(request);
    }

    private ResultActions suspend(String reason) throws Exception {
        return act("suspend", "admin@example.com", "{\"reason\":\"%s\"}".formatted(reason));
    }

    private ResultActions reactivate(String reason) throws Exception {
        return act("reactivate", "admin@example.com", "{\"reason\":\"%s\"}".formatted(reason));
    }

    private ResultActions deactivate(String reason, String slug) throws Exception {
        return act("deactivate", "admin@example.com",
                "{\"reason\":\"%s\",\"confirmSlug\":\"%s\"}".formatted(reason, slug));
    }

    private HotelStatus statusInDb() {
        return hotels.findById(hotelId).orElseThrow().getStatus();
    }

    private List<AdminAuditEntry> entries() {
        return audit.findFirst100ByTargetIdOrderByAtDescIdDesc(hotelId);
    }

    private int staffStatus() throws Exception {
        return mvc.perform(get("/api/v1/hotels/" + hotelId + "/realtime/channels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("owner@example.com")))
                .andReturn().getResponse().getStatus();
    }

    private int scanStatus() throws Exception {
        return mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tok-status\"}")).andReturn().getResponse().getStatus();
    }

    // ---------- the happy paths ----------

    @Test
    void suspendingShutsTheHotelOutAndReactivatingLetsItBackIn() throws Exception {
        assertThat(staffStatus()).isEqualTo(200);
        assertThat(scanStatus()).isEqualTo(200);

        suspend("Unpaid invoice for October").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.statusReason").value("Unpaid invoice for October"));

        assertThat(statusInDb()).isEqualTo(HotelStatus.SUSPENDED);
        assertThat(staffStatus()).as("staff are locked out").isEqualTo(403);
        assertThat(scanStatus()).as("guests cannot scan").isBetween(400, 499);

        reactivate("Invoice paid").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.statusReason").doesNotExist());
        assertThat(staffStatus()).isEqualTo(200);
        assertThat(scanStatus()).isEqualTo(200);
    }

    @Test
    void everyChangeLeavesAnAuditEntryWithWhoWhenAndWhy() throws Exception {
        suspend("Unpaid invoice").andExpect(status().isOk());
        reactivate("Invoice paid").andExpect(status().isOk());

        List<AdminAuditEntry> log = entries(); // newest first
        assertThat(log).hasSize(2);
        AdminAuditEntry latest = log.get(0);
        assertThat(latest.getAction()).isEqualTo(AdminAuditAction.HOTEL_REACTIVATED);
        assertThat(latest.getFromStatus()).isEqualTo("SUSPENDED");
        assertThat(latest.getToStatus()).isEqualTo("ACTIVE");
        assertThat(latest.getReason()).isEqualTo("Invoice paid");
        assertThat(latest.getActorUserId()).isEqualTo(adminId);
        assertThat(latest.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(latest.getTargetId()).isEqualTo(hotelId);
        assertThat(latest.getTargetLabel()).isEqualTo("Status Hotel (status-hotel)");
        assertThat(latest.getAt()).isNotNull();
        assertThat(log.get(1).getAction()).isEqualTo(AdminAuditAction.HOTEL_SUSPENDED);
        assertThat(log.get(1).getFromStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void deactivatingNeedsTheSlugAndCanBeUndone() throws Exception {
        deactivate("Closed down for good", "wrong-slug").andExpect(status().isBadRequest());
        deactivate("Closed down for good", "").andExpect(status().isBadRequest());
        assertThat(statusInDb()).isEqualTo(HotelStatus.ACTIVE);
        assertThat(entries()).isEmpty();

        deactivate("Closed down for good", "status-hotel").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));
        assertThat(staffStatus()).isEqualTo(403);
        assertThat(entries()).hasSize(1);
        assertThat(entries().get(0).getAction()).isEqualTo(AdminAuditAction.HOTEL_DEACTIVATED);

        suspend("Not allowed from deactivated").andExpect(status().isConflict());
        reactivate("Shut down by mistake").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(staffStatus()).isEqualTo(200);
    }

    @Test
    void aSuspendedHotelCanBeDeactivatedWithoutReactivatingFirst() throws Exception {
        suspend("Under review").andExpect(status().isOk());
        deactivate("Review failed", "status-hotel").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.statusReason").value("Review failed"));
    }

    // ---------- refusals leave no trace ----------

    @Test
    void moveThatMakesNoSenseIsRefusedAndWritesNothing() throws Exception {
        reactivate("Already fine").andExpect(status().isConflict()); // active -> active
        suspend("First").andExpect(status().isOk());
        suspend("Again").andExpect(status().isConflict());            // suspended -> suspended

        assertThat(entries()).hasSize(1);
        assertThat(statusInDb()).isEqualTo(HotelStatus.SUSPENDED);
    }

    @Test
    void aReasonIsRequiredAndAnUnknownHotelIsNotFound() throws Exception {
        act("suspend", "admin@example.com", "{}").andExpect(status().isBadRequest());
        act("suspend", "admin@example.com", "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        act("suspend", "admin@example.com", "{\"reason\":\"ab\"}").andExpect(status().isBadRequest());
        act("suspend", "admin@example.com", "{\"reason\":\"%s\"}".formatted("x".repeat(301)))
                .andExpect(status().isBadRequest());
        assertThat(statusInDb()).isEqualTo(HotelStatus.ACTIVE);
        assertThat(entries()).isEmpty();

        mvc.perform(post("/api/v1/admin/hotels/no-such-hotel/suspend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Because\"}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("admin@example.com")))
                .andExpect(status().isNotFound());
    }

    // ---------- who may do it ----------

    @Test
    void onlyAPlatformAdminMayChangeAHotelsStatus() throws Exception {
        String json = "{\"reason\":\"Trying my luck\"}";
        act("suspend", null, json).andExpect(status().isUnauthorized());
        act("suspend", "owner@example.com", json).andExpect(status().isForbidden()); // not even the hotel's owner
        assertThat(statusInDb()).isEqualTo(HotelStatus.ACTIVE);
        assertThat(entries()).isEmpty();
    }

    @Test
    void anAdminWhoseRoleWasRemovedLosesTheirPowerAtOnceEvenWithAValidToken() throws Exception {
        String token = login("admin2@example.com"); // valid for 15 minutes
        mongo.updateFirst(Query.query(Criteria.where("email").is("admin2@example.com")),
                new Update().unset("platformRole"), User.class);

        mvc.perform(post("/api/v1/admin/hotels/" + hotelId + "/suspend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Should not work\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(statusInDb()).isEqualTo(HotelStatus.ACTIVE);
        assertThat(entries()).isEmpty();
    }

    // ---------- two admins at once ----------

    @Test
    void twoAdminsSuspendingAtTheSameTimeIsOneChangeAndOneAuditEntry() throws Exception {
        String t1 = login("admin@example.com");
        String t2 = login("admin2@example.com");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String token : List.of(t1, t2)) {
                Callable<Integer> call = () -> {
                    go.await();
                    return mvc.perform(post("/api/v1/admin/hotels/" + hotelId + "/suspend")
                                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"At the same time\"}")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            Collections.sort(statuses);
            assertThat(statuses).containsExactly(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(entries()).hasSize(1);
        assertThat(statusInDb()).isEqualTo(HotelStatus.SUSPENDED);
    }
}