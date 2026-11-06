package com.example.qrorder.tables.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
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
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class QrCredentialIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String tableId;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, Instant.now())).getId();
    }

    private void seedMember(String hotel, String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
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

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String qrUrl(String hotel, String table) {
        return "/api/v1/hotels/" + hotel + "/tables/" + table + "/qr";
    }

    private String issueToken(String bearer) throws Exception {
        MvcResult result = call(post(qrUrl(hotelId, tableId)), bearer, null)
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private List<QrCredential> activeCredentials() {
        return credentials.findAll().stream().filter(c -> c.getStatus() == QrStatus.ACTIVE).toList();
    }

    @Test
    void issuingReturnsTheTokenOnceAndStoresOnlyItsHash() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);

        MvcResult result = call(post(qrUrl(hotelId, tableId)), login("manager@example.com"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tableId").value(tableId))
                .andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        String scanUrl = JsonPath.read(result.getResponse().getContentAsString(), "$.scanUrl");

        assertThat(token).hasSize(43);
        assertThat(scanUrl).isEqualTo("http://localhost:5173/t/" + token);

        QrCredential stored = credentials.findAll().get(0);
        assertThat(stored.getTokenHash()).isEqualTo(Tokens.sha256(token)).isNotEqualTo(token);
        assertThat(stored.getStatus()).isEqualTo(QrStatus.ACTIVE);
        assertThat(stored.getHotelId()).isEqualTo(hotelId);
        assertThat(stored.getTableId()).isEqualTo(tableId);
    }

    @Test
    void issuingAgainRotatesAndRevokesTheOldCredential() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String bearer = login("manager@example.com");

        String first = issueToken(bearer);
        String second = issueToken(bearer);

        assertThat(second).isNotEqualTo(first);
        assertThat(credentials.count()).isEqualTo(2);
        assertThat(activeCredentials()).hasSize(1);
        assertThat(activeCredentials().get(0).getTokenHash()).isEqualTo(Tokens.sha256(second));

        QrCredential revoked = credentials.findAll().stream()
                .filter(c -> c.getStatus() == QrStatus.REVOKED).findFirst().orElseThrow();
        assertThat(revoked.getTokenHash()).isEqualTo(Tokens.sha256(first));
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevokedBy()).isNotNull();
    }

    @Test
    void statusNeverExposesTheToken() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String bearer = login("manager@example.com");

        call(get(qrUrl(hotelId, tableId)), bearer, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        issueToken(bearer);

        call(get(qrUrl(hotelId, tableId)), bearer, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.issuedAt").exists())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist());
    }

    @Test
    void revokingStopsTheCredentialAndIsIdempotent() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String bearer = login("manager@example.com");
        issueToken(bearer);

        call(delete(qrUrl(hotelId, tableId)), bearer, null).andExpect(status().isNoContent());
        call(delete(qrUrl(hotelId, tableId)), bearer, null).andExpect(status().isNoContent());

        assertThat(activeCredentials()).isEmpty();
        call(get(qrUrl(hotelId, tableId)), bearer, null).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void aWaiterCannotIssueReadOrRevoke() throws Exception {
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        String bearer = login("waiter@example.com");

        call(post(qrUrl(hotelId, tableId)), bearer, null).andExpect(status().isForbidden());
        call(get(qrUrl(hotelId, tableId)), bearer, null).andExpect(status().isForbidden());
        call(delete(qrUrl(hotelId, tableId)), bearer, null).andExpect(status().isForbidden());
        assertThat(credentials.count()).isZero();
    }

    @Test
    void aDeactivatedTableCannotGetAQrAndDeactivatingRevokesIt() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String bearer = login("manager@example.com");
        issueToken(bearer);

        // deactivate through PATCH
        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + tableId), bearer, "{\"active\":false}")
                .andExpect(status().isOk());
        assertThat(activeCredentials()).isEmpty();
        call(post(qrUrl(hotelId, tableId)), bearer, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        // reactivate, issue, then deactivate through DELETE
        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + tableId), bearer, "{\"active\":true}")
                .andExpect(status().isOk());
        issueToken(bearer);
        assertThat(activeCredentials()).hasSize(1);
        call(delete("/api/v1/hotels/" + hotelId + "/tables/" + tableId), bearer, null)
                .andExpect(status().isNoContent());
        assertThat(activeCredentials()).isEmpty();
    }

    @Test
    void aTableFromAnotherHotelIsNotFoundAndGetsNoCredential() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String foreignTable = tables.save(new DiningTable(otherHotelId, "T1", 4, null, Instant.now())).getId();
        String bearer = login("manager@example.com");

        call(post(qrUrl(hotelId, foreignTable)), bearer, null).andExpect(status().isNotFound());
        call(get(qrUrl(hotelId, foreignTable)), bearer, null).andExpect(status().isNotFound());
        call(delete(qrUrl(hotelId, foreignTable)), bearer, null).andExpect(status().isNotFound());
        assertThat(credentials.count()).isZero();
    }

    @Test
    void theDatabaseItselfAllowsOnlyOneActiveCredentialPerTable() {
        Instant now = Instant.now();
        credentials.save(new QrCredential(hotelId, tableId, "hash-a", "u", now));

        assertThatThrownBy(() -> credentials.save(new QrCredential(hotelId, tableId, "hash-b", "u", now)))
                .isInstanceOf(DuplicateKeyException.class);

        // revoked credentials do not count against the limit
        QrCredential old = new QrCredential(hotelId, tableId, "hash-c", "u", now);
        old.revoke("u", now);
        credentials.save(old);
        assertThat(credentials.count()).isEqualTo(2);
    }
}