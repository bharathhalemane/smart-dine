package com.example.qrorder.identity.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class AuthProtectionIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtEncoder jwtEncoder;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

    private User seed(String email) {
        return users.save(new User(email, encoder.encode(PASSWORD), "Test"));
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)));
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        MvcResult result = login(email).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions getWithToken(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static JwtClaimsSet.Builder claimsFor(String userId) {
        return JwtClaimsSet.builder().issuer("qr-order").subject(userId);
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Test
    void requestsWithoutATokenGetAProblemJson401() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void aValidTokenIdentifiesTheCurrentUser() throws Exception {
        seed("me@example.com");
        String token = loginAndGetAccessToken("me@example.com");

        getWithToken("/api/v1/auth/me", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        User user = seed("old@example.com");
        Instant now = Instant.now();
        String token = sign(jwtEncoder, claimsFor(user.getId())
                .issuedAt(now.minus(2, ChronoUnit.HOURS))
                .expiresAt(now.minus(1, ChronoUnit.HOURS))
                .build());

        getWithToken("/api/v1/auth/me", token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        User user = seed("forged@example.com");
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "a-different-secret-a-different-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        String token = sign(attacker, claimsFor(user.getId())
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .build());

        getWithToken("/api/v1/auth/me", token).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenStopsWorkingWhenTheUserIsDisabledOrDeleted() throws Exception {
        seed("disabled@example.com");
        String disabledToken = loginAndGetAccessToken("disabled@example.com");
        seed("deleted@example.com");
        String deletedToken = loginAndGetAccessToken("deleted@example.com");

        User disabled = users.findByEmail("disabled@example.com").orElseThrow();
        disabled.setStatus(UserStatus.DISABLED);
        users.save(disabled);
        users.delete(users.findByEmail("deleted@example.com").orElseThrow());

        getWithToken("/api/v1/auth/me", disabledToken).andExpect(status().isUnauthorized());
        getWithToken("/api/v1/auth/me", deletedToken).andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminGateNeedsThePlatformAdminRole() throws Exception {
        seed("normal@example.com");
        String normalToken = loginAndGetAccessToken("normal@example.com");

        User admin = new User("admin@example.com", encoder.encode(PASSWORD), "Admin");
        admin.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        users.save(admin);
        String adminToken = loginAndGetAccessToken("admin@example.com");

        getWithToken("/api/v1/_probe/admin", normalToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        getWithToken("/api/v1/_probe/admin", adminToken).andExpect(status().isOk());
    }

    @Test
    void aStaleBearerTokenDoesNotBlockThePublicAuthEndpoints() throws Exception {
        seed("stale@example.com");

        mvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage.not.a.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"stale@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk());
    }
}