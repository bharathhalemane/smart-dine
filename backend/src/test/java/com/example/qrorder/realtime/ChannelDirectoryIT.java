package com.example.qrorder.realtime;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
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
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

class ChannelDirectoryIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String guest;

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

        hotelId = hotels.save(new Hotel("dir-hotel", "Dir Hotel", "x", null, "Asia/Kolkata", "INR", now)).getId();
        otherHotelId = hotels.save(new Hotel("dir-other", "Other", "x", null, "Asia/Kolkata", "INR", now)).getId();
        member("owner@example.com", hotelId, HotelRole.OWNER);
        member("cook@example.com", hotelId, HotelRole.KITCHEN);
        member("waiter@example.com", hotelId, HotelRole.WAITER);
        member("cashier@example.com", hotelId, HotelRole.CASHIER);
        member("outsider@example.com", otherHotelId, HotelRole.OWNER);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-dir"), "u", now));
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-dir\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        matcher.find();
        guest = matcher.group(1);
    }

    private void member(String email, String hotel, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), email);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private ResultActions staffCall(String email, String hotel) throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        MockHttpServletRequestBuilder request = get("/api/v1/hotels/" + hotel + "/realtime/channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return mvc.perform(request);
    }

    @Test
    void theOwnerGetsAllThreeChannelsWithTheirReloadCalls() throws Exception {
        staffCall("owner@example.com", hotelId).andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").exists())
                .andExpect(jsonPath("$.channels[*].name").value(containsInAnyOrder("kitchen", "waiters", "billing")))
                .andExpect(jsonPath("$.channels[?(@.name=='kitchen')].topic").value(Topics.kitchen(hotelId)))
                .andExpect(jsonPath("$.channels[?(@.name=='kitchen')].reload[0]")
                        .value("/api/v1/hotels/" + hotelId + "/kitchen/orders"))
                .andExpect(jsonPath("$.channels[?(@.name=='billing')].reload[0]")
                        .value("/api/v1/hotels/" + hotelId + "/payments/waiting-orders"));
    }

    @Test
    void eachRoleSeesOnlyItsOwnChannel() throws Exception {
        staffCall("cook@example.com", hotelId).andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[*].name").value(containsInAnyOrder("kitchen")));
        staffCall("waiter@example.com", hotelId).andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[*].name").value(containsInAnyOrder("waiters")));
        staffCall("cashier@example.com", hotelId).andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[*].name").value(containsInAnyOrder("billing")));
    }

    @Test
    void someoneFromAnotherHotelCannotAskAboutThisOne() throws Exception {
        staffCall("outsider@example.com", hotelId).andExpect(status().isNotFound());
    }

    @Test
    void aGuestGetsTheirTableChannel() throws Exception {
        mvc.perform(get("/api/v1/customer/realtime").cookie(new Cookie("qr_customer", guest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(1))
                .andExpect(jsonPath("$.channels[0].name").value("table"))
                .andExpect(jsonPath("$.channels[0].topic").value(
                        org.hamcrest.Matchers.startsWith("/topic/tables/")))
                .andExpect(jsonPath("$.channels[0].reload[0]").value("/api/v1/customer/orders"))
                .andExpect(jsonPath("$.channels[0].reload[1]").value("/api/v1/customer/bill"));
    }

    @Test
    void withoutACookieOrWithABadOneTheGuestCallIsRefused() throws Exception {
        mvc.perform(get("/api/v1/customer/realtime")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customer/realtime").cookie(new Cookie("qr_customer", "nonsense")))
                .andExpect(status().isUnauthorized());
    }
}