package com.example.qrorder.tables.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class QrSheetIT extends AbstractIntegrationTest {

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
    String t1;
    String t2;

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
        t1 = tables.save(new DiningTable(hotelId, "T1", 4, "Terrace", Instant.now())).getId();
        t2 = tables.save(new DiningTable(hotelId, "T2", 2, null, Instant.now())).getId();
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

    private ResultActions sheet(String bearer, String json) throws Exception {
        var request = post("/api/v1/hotels/" + hotelId + "/qr-sheet")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private List<QrCredential> active() {
        return credentials.findAll().stream().filter(c -> c.getStatus() == QrStatus.ACTIVE).toList();
    }

    @Test
    void withNoBodyItPrintsEveryActiveTableAndSkipsInactiveOnes() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        DiningTable off = new DiningTable(hotelId, "T3", 4, null, Instant.now());
        off.setActive(false);
        tables.save(off);

        sheet(login("manager@example.com"), null)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/html")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(content().string(containsString("Spice Garden")))
                .andExpect(content().string(containsString("Table T1")))
                .andExpect(content().string(containsString("Table T2")))
                .andExpect(content().string(not(containsString("Table T3"))))
                .andExpect(content().string(containsString("data:image/png;base64,")));

        assertThat(active()).hasSize(2);
    }

    @Test
    void selectedTablesOnlyAreRotated() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);

        sheet(login("manager@example.com"), "{\"tableIds\":[\"%s\"]}".formatted(t1))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Table T1")))
                .andExpect(content().string(not(containsString("Table T2"))));

        assertThat(active()).hasSize(1);
        assertThat(active().get(0).getTableId()).isEqualTo(t1);
    }

    @Test
    void printingAgainRotatesSoTheOldStickersStopWorking() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String bearer = login("manager@example.com");

        sheet(bearer, null).andExpect(status().isOk());
        List<String> firstHashes = active().stream().map(QrCredential::getTokenHash).toList();
        sheet(bearer, null).andExpect(status().isOk());

        assertThat(credentials.count()).isEqualTo(4);
        assertThat(active()).hasSize(2);
        assertThat(active().stream().map(QrCredential::getTokenHash)).doesNotContainAnyElementsOf(firstHashes);
    }

    @Test
    void aWaiterCannotPrintAndNothingIsIssued() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);

        sheet(login("waiter@example.com"), null).andExpect(status().isForbidden());
        assertThat(credentials.count()).isZero();
    }

    @Test
    void oneForeignOrInactiveTableFailsTheWholeRequestAndRotatesNothing() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String bearer = login("manager@example.com");
        String foreign = tables.save(new DiningTable(otherHotelId, "T1", 4, null, Instant.now())).getId();
        DiningTable off = new DiningTable(hotelId, "T9", 4, null, Instant.now());
        off.setActive(false);
        String inactive = tables.save(off).getId();

        sheet(bearer, "{\"tableIds\":[\"%s\",\"%s\"]}".formatted(t1, foreign)).andExpect(status().isNotFound());
        sheet(bearer, "{\"tableIds\":[\"%s\",\"%s\"]}".formatted(t1, inactive)).andExpect(status().isConflict());

        assertThat(credentials.count()).isZero();
    }

    @Test
    void aHotelWithNoTablesGetsAClearError() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        tables.deleteAll();

        sheet(login("manager@example.com"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void hostileSectionTextIsEscapedAndIssueNowReturnsTheImage() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String bearer = login("manager@example.com");
        tables.save(new DiningTable(hotelId, "T7", 4, "<script>alert(1)</script>", Instant.now()));

        sheet(bearer, null)
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)"))))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));

        mvc.perform(post("/api/v1/hotels/" + hotelId + "/tables/" + t1 + "/qr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qrImage", startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.token").exists());
    }
}