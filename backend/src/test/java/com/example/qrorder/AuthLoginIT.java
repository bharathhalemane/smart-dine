package com.example.qrorder.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.model.UserStatus;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class AuthLoginIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

    private User seed(String email) {
        return users.save(new User(email, encoder.encode(PASSWORD), "Test"));
    }

    private ResultActions login(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, password);
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void loginReturnsAnAccessTokenForTheUser() throws Exception {
        User user = seed("owner@example.com");

        MvcResult result = login("OWNER@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.user.email").value("owner@example.com"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo(user.getId());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameResponse() throws Exception {
        seed("known@example.com");

        login("known@example.com", "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));

        login("nobody@example.com", "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void accountLocksAfterFiveFailuresAndRecoversAfterTheLockExpires() throws Exception {
        seed("lock@example.com");

        for (int i = 0; i < 5; i++) {
            login("lock@example.com", "wrong-password-123").andExpect(status().isUnauthorized());
        }
        // The correct password is refused while the account is locked.
        login("lock@example.com", PASSWORD).andExpect(status().isUnauthorized());

        User locked = users.findByEmail("lock@example.com").orElseThrow();
        assertThat(locked.getLockedUntil()).isNotNull();

        locked.setLockedUntil(Instant.now().minusSeconds(60));
        users.save(locked);

        login("lock@example.com", PASSWORD).andExpect(status().isOk());

        User after = users.findByEmail("lock@example.com").orElseThrow();
        assertThat(after.getFailedLoginCount()).isZero();
        assertThat(after.getLockedUntil()).isNull();
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        User user = seed("disabled@example.com");
        user.setStatus(UserStatus.DISABLED);
        users.save(user);

        login("disabled@example.com", PASSWORD).andExpect(status().isUnauthorized());
    }
}