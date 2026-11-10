package com.example.qrorder.common.ratelimit;

import org.springframework.core.env.Environment;

/** Every limit, in requests per window. Read from app.rate-limit.* with the defaults below. */
public record RateLimitSettings(int loginPerMinute, int refreshPerMinute, int sensitiveAuthPer15Minutes,
                                int scanPerMinute, int orderPerMinutePerGuest, int orderPerMinutePerIp,
                                int generalPerMinute) {

    public RateLimitSettings {
        if (loginPerMinute < 1 || refreshPerMinute < 1 || sensitiveAuthPer15Minutes < 1 || scanPerMinute < 1
                || orderPerMinutePerGuest < 1 || orderPerMinutePerIp < 1 || generalPerMinute < 1) {
            throw new IllegalArgumentException("Every app.rate-limit.* value must be 1 or more");
        }
    }

    public static RateLimitSettings from(Environment env) {
        String p = "app.rate-limit.";
        return new RateLimitSettings(
                env.getProperty(p + "login-per-minute", Integer.class, 10),
                env.getProperty(p + "refresh-per-minute", Integer.class, 30),
                env.getProperty(p + "sensitive-auth-per-15-minutes", Integer.class, 10),
                env.getProperty(p + "scan-per-minute", Integer.class, 60),
                env.getProperty(p + "order-per-minute-per-guest", Integer.class, 20),
                env.getProperty(p + "order-per-minute-per-ip", Integer.class, 200),
                env.getProperty(p + "general-per-minute", Integer.class, 600));
    }
}