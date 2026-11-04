package com.example.qrorder.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.model.RefreshToken;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.repository.RefreshTokenRepository;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class AuthSessionIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String COOKIE = "refresh_token";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void clean() {
        users.deleteAll();
        refreshTokens.deleteAll();
    }

    private User seed(String email) {
        return users.save(new User(email, encoder.encode(PASSWORD), "Test"));
    }

    private ResultActions login(String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD);
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String loginAndGetRefreshToken(String email) throws Exception {
        MvcResult result = login(email).andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie(COOKIE).getValue();
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh")
                .header("X-Requested-With", "XMLHttpRequest")
                .cookie(new Cookie(COOKIE, token)));
    }

    @Test
    void loginSetsAHardenedCookieAndStoresOnlyTheHash() throws Exception {
        seed("a@example.com");

        MvcResult result = login("a@example.com")
                .andExpect(status().isOk())
                .andExpect(cookie().exists(COOKIE))
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(cookie().secure(COOKIE, true))
                .andExpect(cookie().path(COOKIE, "/api/v1/auth"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("SameSite=Strict")))
                .andReturn();

        String plain = result.getResponse().getCookie(COOKIE).getValue();
        assertThat(refreshTokens.findByTokenHash(Tokens.sha256(plain))).isPresent();
        assertThat(refreshTokens.findByTokenHash(plain)).isEmpty();
    }

    @Test
    void refreshRotatesTheTokenAndReturnsANewAccessToken() throws Exception {
        seed("b@example.com");
        String first = loginAndGetRefreshToken("b@example.com");

        MvcResult result = refresh(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("b@example.com"))
                .andReturn();

        String second = result.getResponse().getCookie(COOKIE).getValue();
        assertThat(second).isNotEqualTo(first);
        refresh(second).andExpect(status().isOk());
    }

    @Test
    void reusingAnOldTokenRevokesTheWholeFamily() throws Exception {
        seed("c@example.com");
        String first = loginAndGetRefreshToken("c@example.com");
        String second = refresh(first).andReturn().getResponse().getCookie(COOKIE).getValue();

        refresh(first).andExpect(status().isUnauthorized());   // replay of the used token
        refresh(second).andExpect(status().isUnauthorized());  // the legitimate successor is revoked too
    }

    @Test
    void refreshNeedsTheGuardHeaderAndTheCookie() throws Exception {
        seed("d@example.com");
        String token = loginAndGetRefreshToken("d@example.com");

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        seed("e@example.com");
        String token = loginAndGetRefreshToken("e@example.com");

        mvc.perform(post("/api/v1/auth/logout")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(COOKIE, 0));

        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenAndDisabledUserAreRejected() throws Exception {
        User user = seed("f@example.com");

        String expiredPlain = Tokens.newToken();
        refreshTokens.save(new RefreshToken(Tokens.sha256(expiredPlain), user.getId(), "fam-1",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)));
        refresh(expiredPlain).andExpect(status().isUnauthorized());

        String live = loginAndGetRefreshToken("f@example.com");
        user = users.findByEmail("f@example.com").orElseThrow();
        user.setStatus(UserStatus.DISABLED);
        users.save(user);
        refresh(live).andExpect(status().isUnauthorized());
    }
}