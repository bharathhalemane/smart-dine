package com.example.qrorder.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class AuthControllerIT extends AbstractIntegrationTest{
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    @BeforeEach
    void clean(){
        users.deleteAll();
    }

    private ResultActions register(String email, String password, String name) throws Exception{
        String body = """
                {"email":"%s", "password":"%s", "name":"%s"}
                """.formatted(email,password,name);
        return mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void registersUserAndStoresOnlyHash() throws Exception{
        register("Owner@Example.com", "correct-horse-battery","Asha")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
        User saved = users.findByEmail("owner@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("correct-horse-battery");
    }

    @Test
    void duplicateEmailIgnoreingCaseIsRejected() throws Exception{
        register("dup@example.com","correct-horse-battery","A")
                .andExpect(status().isCreated());

        register("DUP@example.com", "another-long-password","B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void shortPasswordIsRejected() throws Exception{
        register("short@example.com","tooshort","A")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void invalidEmailIsRejected() throws Exception{
        register("not-an-email","another-long-password","C")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

}
