package com.example.qrorder.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private static final long SECOND = 1_000_000_000L;

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final AtomicLong now = new AtomicLong(0);
    // login 3/min, refresh 5/min, sensitive 2/15min, scan 4/min, order guest 2/min, order ip 6/min, general 8/min
    private final RateLimitFilter filter = new RateLimitFilter(mapper,
            new RateLimitSettings(3, 5, 2, 4, 2, 6, 8), now::get);

    private MockHttpServletResponse call(String method, String uri, String ip, String guestCookie) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(ip);
        if (guestCookie != null) {
            request.setCookies(new Cookie("qr_customer", guestCookie));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private int status(String method, String uri, String ip) throws Exception {
        return call(method, uri, ip, null).getStatus();
    }

    @Test
    void loginIsLimitedPerAddressAndRecovers() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(status("POST", "/api/v1/auth/login", "1.1.1.1")).isEqualTo(200);
        }
        MockHttpServletResponse refused = call("POST", "/api/v1/auth/login", "1.1.1.1", null);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isNotBlank();
        assertThat(refused.getContentAsString()).contains("RATE_LIMITED");

        assertThat(status("POST", "/api/v1/auth/login", "2.2.2.2")).as("another address").isEqualTo(200);

        now.addAndGet(21 * SECOND); // 3 per minute = one token every 20 seconds
        assertThat(status("POST", "/api/v1/auth/login", "1.1.1.1")).isEqualTo(200);
    }

    @Test
    void guestsOnTheSameWifiAreCountedSeparately() throws Exception {
        for (int i = 0; i < 2; i++) {
            assertThat(call("POST", "/api/v1/customer/orders", "9.9.9.9", "guest-one").getStatus()).isEqualTo(200);
        }
        assertThat(call("POST", "/api/v1/customer/orders", "9.9.9.9", "guest-one").getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/v1/customer/orders", "9.9.9.9", "guest-two").getStatus()).isEqualTo(200);
    }

    @Test
    void rotatingCookiesDoesNotEscapeTheAddressLimit() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThat(call("POST", "/api/v1/customer/orders", "8.8.8.8", "junk-" + i).getStatus()).isEqualTo(200);
        }
        assertThat(call("POST", "/api/v1/customer/orders", "8.8.8.8", "junk-new").getStatus()).isEqualTo(429);
    }

    @Test
    void readingOrdersIsNotAnOrderWrite() throws Exception {
        for (int i = 0; i < 7; i++) {
            assertThat(call("GET", "/api/v1/customer/orders", "7.7.7.7", "same-guest").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void sensitiveAuthRoutesShareOneTightBucketButLogoutIsLeftAlone() throws Exception {
        assertThat(status("POST", "/api/v1/auth/register", "3.3.3.3")).isEqualTo(200);
        assertThat(status("POST", "/api/v1/auth/forgot-password", "3.3.3.3")).isEqualTo(200);
        assertThat(status("POST", "/api/v1/auth/reset-password", "3.3.3.3")).isEqualTo(429);
        assertThat(status("POST", "/api/v1/auth/some-future-route", "3.3.3.3")).as("safe by default").isEqualTo(429);
        for (int i = 0; i < 5; i++) {
            assertThat(status("POST", "/api/v1/auth/logout", "3.3.3.3")).isEqualTo(200);
        }
    }

    @Test
    void scanAndRefreshHaveTheirOwnLimits() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(status("POST", "/api/v1/scan", "4.4.4.4")).isEqualTo(200);
        }
        assertThat(status("POST", "/api/v1/scan", "4.4.4.4")).isEqualTo(429);

        for (int i = 0; i < 3; i++) { // 4 scans used so far; general allows 8 in total
            assertThat(status("POST", "/api/v1/auth/refresh", "5.5.5.5")).isEqualTo(200);
        }
    }

    @Test
    void theGeneralCapCoversEverythingElse() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertThat(status("GET", "/api/v1/hotels", "6.6.6.6")).isEqualTo(200);
        }
        assertThat(status("GET", "/api/v1/hotels", "6.6.6.6")).isEqualTo(429);
        assertThat(status("GET", "/api/v1/hotels", "6.6.6.7")).isEqualTo(200);
    }

    @Test
    void healthChecksAndCorsPreflightAreNeverLimited() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(status("GET", "/actuator/health", "10.0.0.1")).isEqualTo(200);
            assertThat(status("OPTIONS", "/api/v1/auth/login", "10.0.0.1")).isEqualTo(200);
        }
    }

    @Test
    void settingsCanBeChangedAndAreChecked() {
        RateLimitSettings custom = RateLimitSettings.from(
                new MockEnvironment().withProperty("app.rate-limit.login-per-minute", "3"));
        assertThat(custom.loginPerMinute()).isEqualTo(3);
        assertThat(custom.generalPerMinute()).isEqualTo(600); // untouched default

        assertThatThrownBy(() -> RateLimitSettings.from(
                new MockEnvironment().withProperty("app.rate-limit.scan-per-minute", "0")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}