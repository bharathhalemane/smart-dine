package com.example.qrorder.common.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class CorsIT extends AbstractIntegrationTest {

    private static final String GOOD = "http://localhost:5173";
    private static final String EVIL = "https://evil.example";

    @Autowired MockMvc mvc;

    private ResultActions preflight(String origin, String method, String headers) throws Exception {
        var request = options("/api/v1/auth/login").header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (headers != null) {
            request.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers);
        }
        return mvc.perform(request);
    }

    @Test
    void theFrontendOriginIsAllowedWithCredentials() throws Exception {
        preflight(GOOD, "POST", "content-type,idempotency-key").andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, GOOD)) // echoed, never "*"
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
    }

    @Test
    void anyOtherOriginIsRefused() throws Exception {
        preflight(EVIL, "POST", "content-type").andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, EVIL)).andExpect(status().isForbidden());
    }

    @Test
    void unlistedMethodsAndHeadersAreRefusedEvenFromTheFrontend() throws Exception {
        preflight(GOOD, "TRACE", null).andExpect(status().isForbidden());
        preflight(GOOD, "POST", "x-evil-header").andExpect(status().isForbidden());
    }

    @Test
    void theTraceIdHeaderIsReadableByTheBrowser() throws Exception {
        mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, GOOD)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Trace-Id")));
    }
}