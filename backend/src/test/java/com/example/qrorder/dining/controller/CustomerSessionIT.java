package com.example.qrorder.dining.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.CustomerSession;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.repository.HotelRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class CustomerSessionIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @Autowired MockMvc mvc;
    @Autowired HotelRepository hotels;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;

    String hotelId;

    @BeforeEach
    void seed() {
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        seedTable("T1", "tok-t1");
        seedTable("T2", "tok-t2");
    }

    private void seedTable(String number, String plainToken) {
        String id = tables.save(new DiningTable(hotelId, number, 4, null, Instant.now())).getId();
        credentials.save(new QrCredential(hotelId, id, Tokens.sha256(plainToken), "u", Instant.now()));
    }

    private MvcResult scan(String qrToken, String cookie) throws Exception {
        var request = post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(qrToken));
        if (cookie != null) {
            request.cookie(new Cookie("qr_customer", cookie));
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private static String setCookieHeader(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    private static String tokenFrom(MvcResult result) {
        String header = setCookieHeader(result);
        assertThat(header).as("a Set-Cookie header").isNotNull();
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Test
    void aFirstScanIssuesAnHttpOnlyCookieAndStoresOnlyItsHash() throws Exception {
        MvcResult result = scan("tok-t1", null);

        String header = setCookieHeader(result);
        assertThat(header).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api/v1");
        String token = tokenFrom(result);
        assertThat(token).hasSizeGreaterThanOrEqualTo(40);

        // the token is never in the body
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);

        CustomerSession stored = customers.findAll().get(0);
        assertThat(stored.getTokenHash()).isEqualTo(Tokens.sha256(token)).isNotEqualTo(token);
        DiningSession session = sessions.findAll().get(0);
        assertThat(stored.getDiningSessionId()).isEqualTo(session.getId());
        assertThat(stored.getHotelId()).isEqualTo(hotelId);
        assertThat(stored.getTableId()).isEqualTo(session.getTableId());
    }

    @Test
    void scanningAgainWithTheCookieKeepsTheSameIdentity() throws Exception {
        String token = tokenFrom(scan("tok-t1", null));

        MvcResult again = scan("tok-t1", token);

        assertThat(setCookieHeader(again)).isNull();
        assertThat(customers.count()).isEqualTo(1);
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void aSecondDinerWithoutTheCookieGetsTheirOwnIdentityOnTheSameTableSession() throws Exception {
        String first = tokenFrom(scan("tok-t1", null));
        String second = tokenFrom(scan("tok-t1", null));

        assertThat(second).isNotEqualTo(first);
        assertThat(customers.count()).isEqualTo(2);
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(customers.findAll()).extracting(CustomerSession::getDiningSessionId).containsOnly(
                sessions.findAll().get(0).getId());
    }

    @Test
    void meReturnsWhereTheCustomerIsSittingAndNothingInternal() throws Exception {
        String token = tokenFrom(scan("tok-t1", null));

        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelName").value("Spice Garden"))
                .andExpect(jsonPath("$.tableNumber").value("T1"))
                .andExpect(jsonPath("$.sessionStatus").value("OPEN"))
                .andExpect(jsonPath("$.hotelId").doesNotExist())
                .andExpect(jsonPath("$.tableId").doesNotExist());
    }

    @Test
    void missingOrUnknownCookiesAreUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/customer/me"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", "garbage")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void whenTheTableSessionClosesTheIdentityStopsWorkingAndARescanStartsFresh() throws Exception {
        String oldToken = tokenFrom(scan("tok-t1", null));
        DiningSession session = sessions.findAll().get(0);
        session.close(Instant.now());
        sessions.save(session);

        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", oldToken)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));

        // scanning again, even carrying the old cookie, opens a new visit with a new identity
        MvcResult rescan = scan("tok-t1", oldToken);
        String newToken = tokenFrom(rescan);
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(sessions.count()).isEqualTo(2);
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", newToken)))
                .andExpect(status().isOk());
    }

    @Test
    void scanningADifferentTableGivesANewIdentityAndLeavesTheOldOneIntact() throws Exception {
        String atT1 = tokenFrom(scan("tok-t1", null));

        String atT2 = tokenFrom(scan("tok-t2", atT1));

        assertThat(atT2).isNotEqualTo(atT1);
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", atT1)))
                .andExpect(jsonPath("$.tableNumber").value("T1"));
        mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", atT2)))
                .andExpect(jsonPath("$.tableNumber").value("T2"));
    }

    @Test
    void aBadQrCreatesNoIdentity() throws Exception {
        mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"nope\"}"))
                .andExpect(status().isNotFound());

        assertThat(customers.count()).isZero();
    }
}