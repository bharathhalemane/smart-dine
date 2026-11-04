package com.example.qrorder.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.model.PasswordResetToken;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.PasswordResetTokenRepository;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class PasswordResetIT extends AbstractIntegrationTest {

    private static final String OLD = "old-password-123";
    private static final String NEW = "brand-new-password-456";
    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void clean() {
        users.deleteAll();
        resetTokens.deleteAll();
    }

    private User seed(String email) {
        return users.save(new User(email, encoder.encode(OLD), "Test"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password)));
    }

    /** Extracts the token from the one email sent to this address. */
    private String tokenFromSentEmail(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(email), anyString(), body.capture());
        Matcher matcher = TOKEN_IN_LINK.matcher(body.getValue());
        assertThat(matcher.find()).as("reset link in email").isTrue();
        return matcher.group(1);
    }

    @Test
    void resetChangesThePasswordAndTheLinkWorksOnlyOnce() throws Exception {
        seed("a@example.com");
        forgot("a@example.com").andExpect(status().isAccepted());
        String token = tokenFromSentEmail("a@example.com");
        assertThat(resetTokens.findByTokenHash(token)).isEmpty(); // only the hash is stored

        reset(token, NEW).andExpect(status().isNoContent());

        login("a@example.com", OLD).andExpect(status().isUnauthorized());
        login("a@example.com", NEW).andExpect(status().isOk());
        reset(token, "another-password-789").andExpect(status().isBadRequest());
    }

    @Test
    void resetSignsOutEveryExistingSession() throws Exception {
        seed("s@example.com");
        String refreshToken = login("s@example.com", OLD).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token").getValue();

        forgot("s@example.com").andExpect(status().isAccepted());
        reset(tokenFromSentEmail("s@example.com"), NEW).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetClearsALockoutAndVerifiesTheEmail() throws Exception {
        User user = new User("l@example.com", encoder.encode(OLD), "Test");
        user.setFailedLoginCount(5);
        user.setLockedUntil(Instant.now().plusSeconds(900));
        users.save(user);

        forgot("l@example.com").andExpect(status().isAccepted());
        reset(tokenFromSentEmail("l@example.com"), NEW).andExpect(status().isNoContent());

        login("l@example.com", NEW).andExpect(status().isOk());
        assertThat(users.findByEmail("l@example.com").orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void aWeakNewPasswordIsRejectedWithoutBurningTheLink() throws Exception {
        seed("w@example.com");
        forgot("w@example.com").andExpect(status().isAccepted());
        String token = tokenFromSentEmail("w@example.com");

        reset(token, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        reset(token, NEW).andExpect(status().isNoContent());
    }

    @Test
    void unknownAndExpiredTokensAreRejected() throws Exception {
        reset(Tokens.newToken(), NEW)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        User user = seed("e@example.com");
        String plain = Tokens.newToken();
        resetTokens.save(new PasswordResetToken(Tokens.sha256(plain), user.getId(),
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(3700)));

        reset(plain, NEW).andExpect(status().isBadRequest());
        login("e@example.com", OLD).andExpect(status().isOk()); // password unchanged
    }

    @Test
    void forgotPasswordRevealsNothingAndRespectsTheCooldown() throws Exception {
        forgot("nobody@example.com").andExpect(status().isAccepted());
        verifyNoInteractions(emailSender);

        seed("known@example.com");
        forgot("known@example.com").andExpect(status().isAccepted());
        forgot("known@example.com").andExpect(status().isAccepted()); // inside the cooldown: skipped

        verify(emailSender, times(1)).send(eq("known@example.com"), anyString(), anyString());
    }
}