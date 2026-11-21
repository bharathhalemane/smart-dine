package com.example.qrorder.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.admin.repository.AdminAuditRepository;
import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class PayoutAccountApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired AdminAuditRepository audit;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String admin;
    String owner;

    @BeforeEach
    void seed() throws Exception {
        users.deleteAll();
        hotels.deleteAll();
        audit.deleteAll();
        User a = account("admin@example.com");
        a.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        users.save(a);
        User o = users.save(account("owner@example.com"));
        hotelId = hotels.save(new Hotel("acct-hotel", "Acct Hotel", o.getId(), null, "Asia/Kolkata", "INR", Instant.now()))
                .getId();
        admin = login("admin@example.com");
        owner = login("owner@example.com");
    }

    private User account(String email) {
        User user = new User(email, encoder.encode(PASSWORD), email);
        user.setEmailVerified(true);
        return user;
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions putAs(String token, String json) throws Exception {
        var request = put("/api/v1/admin/hotels/" + hotelId + "/payout-account")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private ResultActions getAs(String token) throws Exception {
        var request = get("/api/v1/admin/hotels/" + hotelId + "/payout-account");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    @Test
    void anAdminSetsTheAccountAndItIsAudited() throws Exception {
        getAs(admin).andExpect(status().isOk()).andExpect(jsonPath("$.linkedAccountId").doesNotExist());

        putAs(admin, "{\"linkedAccountId\":\"acc_hotelAAAA1\",\"reason\":\"Verified by Razorpay\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.linkedAccountId").value("acc_hotelAAAA1"));

        getAs(admin).andExpect(jsonPath("$.linkedAccountId").value("acc_hotelAAAA1"));
        List<AdminAuditEntry> entries = audit.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo(AdminAuditAction.HOTEL_PAYOUT_ACCOUNT_CHANGED);
    }

    @Test
    void settingTheSameAccountAgainWritesNoSecondAuditEntry() throws Exception {
        String body = "{\"linkedAccountId\":\"acc_hotelAAAA1\",\"reason\":\"Verified by Razorpay\"}";
        putAs(admin, body).andExpect(status().isOk());
        putAs(admin, body).andExpect(status().isOk());

        assertThat(audit.count()).isEqualTo(1);
    }

    @Test
    void aBadIdOrMissingReasonIsRefused() throws Exception {
        putAs(admin, "{\"linkedAccountId\":\"hotel-12345\",\"reason\":\"Verified by Razorpay\"}").andExpect(status().isBadRequest());
        putAs(admin, "{\"linkedAccountId\":\"acc_hotelAAAA1\",\"reason\":\"\"}").andExpect(status().isBadRequest());
        assertThat(audit.count()).isZero();
    }

    @Test
    void onlyAPlatformAdminCanUseIt() throws Exception {
        String body = "{\"linkedAccountId\":\"acc_hotelAAAA1\",\"reason\":\"Verified by Razorpay\"}";
        putAs(owner, body).andExpect(status().isForbidden());
        putAs(null, body).andExpect(status().isUnauthorized());
        getAs(owner).andExpect(status().isForbidden());
    }
}