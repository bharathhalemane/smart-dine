package com.example.qrorder.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.model.EmailVerificationToken;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.EmailVerificationTokenRepository;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class EmailVerificationIT extends AbstractIntegrationTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired EmailVerificationTokenRepository tokens;

    @BeforeEach
    void clean() {
        users.deleteAll();
        tokens.deleteAll();
    }

    private void register(String email) throws Exception {
        String body = "{\"email\":\"%s\",\"password\":\"correct-horse-battery\",\"name\":\"Asha\"}".formatted(email);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private ResultActions verifyEmail(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token)));
    }

    private ResultActions resend(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
    }

    /** Extracts the token from the one email sent to this address. */
    private String tokenFromSentEmail(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(email), anyString(), body.capture());
        Matcher matcher = TOKEN_IN_LINK.matcher(body.getValue());
        assertThat(matcher.find()).as("verification link in email").isTrue();
        return matcher.group(1);
    }

    @Test
    void registrationEmailsALinkThatVerifiesTheAccountExactlyOnce() throws Exception {
        register("v@example.com");
        assertThat(users.findByEmail("v@example.com").orElseThrow().isEmailVerified()).isFalse();
        String token = tokenFromSentEmail("v@example.com");

        assertThat(tokens.findByTokenHash(token)).isEmpty(); // only the hash is stored

        verifyEmail(token).andExpect(status().isNoContent());
        assertThat(users.findByEmail("v@example.com").orElseThrow().isEmailVerified()).isTrue();

        verifyEmail(token).andExpect(status().isBadRequest()); // single use
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        verifyEmail(Tokens.newToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void expiredTokenIsRejectedAndLeavesTheUserUnverified() throws Exception {
        User user = users.save(new User("x@example.com", "hash", "X"));
        String plain = Tokens.newToken();
        tokens.save(new EmailVerificationToken(Tokens.sha256(plain), user.getId(),
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)));

        verifyEmail(plain).andExpect(status().isBadRequest());

        assertThat(users.findByEmail("x@example.com").orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void resendRespectsTheCooldown() throws Exception {
        register("r@example.com"); // email #1

        resend("r@example.com").andExpect(status().isAccepted()); // inside cooldown: silently skipped
        verify(emailSender, times(1)).send(eq("r@example.com"), anyString(), anyString());

        User user = users.findByEmail("r@example.com").orElseThrow();
        tokens.deleteAll();
        tokens.save(new EmailVerificationToken(Tokens.sha256("old-token"), user.getId(),
                Instant.now().plusSeconds(3600), Instant.now().minusSeconds(120)));

        resend("r@example.com").andExpect(status().isAccepted()); // cooldown over: sends again
        verify(emailSender, times(2)).send(eq("r@example.com"), anyString(), anyString());
    }

    @Test
    void resendRevealsNothingAndSkipsUnknownOrAlreadyVerifiedUsers() throws Exception {
        resend("nobody@example.com").andExpect(status().isAccepted());

        User verified = new User("ok@example.com", "hash", "Ok");
        verified.setEmailVerified(true);
        users.save(verified);
        resend("ok@example.com").andExpect(status().isAccepted());

        verifyNoInteractions(emailSender);
    }

    @Test
    void registrationStillSucceedsWhenTheMailServerIsDown() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(emailSender).send(anyString(), anyString(), anyString());

        register("down@example.com");

        assertThat(users.existsByEmail("down@example.com")).isTrue();
    }
}