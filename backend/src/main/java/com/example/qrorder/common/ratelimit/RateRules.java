package com.example.qrorder.common.ratelimit;

import com.example.qrorder.common.security.Tokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** The rule list, most specific first. The first rule a request breaks refuses it. */
final class RateRules {

    /** Name of the guest's table cookie (the same value as CustomerCookies.NAME, kept here so common does not depend on dining). */
    private static final String CUSTOMER_COOKIE = "qr_customer";
    private static final String AUTH = "/api/v1/auth/";
    private static final Set<String> AUTH_EXEMPT = Set.of(AUTH + "login", AUTH + "refresh", AUTH + "logout");
    private static final String ORDERS = "/api/v1/customer/orders";

    private RateRules() {}

    static List<RateRule> build(RateLimitSettings s) {
        Duration minute = Duration.ofMinutes(1);
        return List.of(
                new RateRule("login", r -> post(r) && is(r, AUTH + "login"), s.loginPerMinute(), minute,
                        RateRules::ip),
                new RateRule("refresh", r -> post(r) && is(r, AUTH + "refresh"), s.refreshPerMinute(), minute,
                        RateRules::ip),
                new RateRule("auth-sensitive",
                        r -> post(r) && r.getRequestURI().startsWith(AUTH) && !AUTH_EXEMPT.contains(r.getRequestURI()),
                        s.sensitiveAuthPer15Minutes(), Duration.ofMinutes(15), RateRules::ip),
                new RateRule("scan", r -> post(r) && is(r, "/api/v1/scan"), s.scanPerMinute(), minute,
                        RateRules::ip),
                new RateRule("order-guest", RateRules::orderWrite, s.orderPerMinutePerGuest(), minute,
                        RateRules::guestOrIp),
                new RateRule("order-ip", RateRules::orderWrite, s.orderPerMinutePerIp(), minute, RateRules::ip),
                new RateRule("general", r -> true, s.generalPerMinute(), minute, RateRules::ip));
    }

    private static boolean post(HttpServletRequest r) {
        return "POST".equals(r.getMethod());
    }

    private static boolean is(HttpServletRequest r, String path) {
        return path.equals(r.getRequestURI());
    }

    private static boolean orderWrite(HttpServletRequest r) {
        String uri = r.getRequestURI();
        boolean read = "GET".equals(r.getMethod()) || "HEAD".equals(r.getMethod());
        return !read && (uri.equals(ORDERS) || uri.startsWith(ORDERS + "/"));
    }

    private static String ip(HttpServletRequest r) {
        return r.getRemoteAddr();
    }

    /** A guest is told apart by their cookie (hashed, never stored raw); no cookie means the address. */
    private static String guestOrIp(HttpServletRequest r) {
        Cookie[] cookies = r.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                String value = c.getValue();
                if (CUSTOMER_COOKIE.equals(c.getName()) && value != null && !value.isBlank() && value.length() <= 512) {
                    return "guest:" + Tokens.sha256(value);
                }
            }
        }
        return ip(r);
    }
}