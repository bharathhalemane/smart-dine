package com.example.qrorder.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        @DefaultValue("1h") Duration ttl,
        @DefaultValue("60s") Duration resendCooldown,
        @DefaultValue("http://localhost:5173") String linkBaseUrl) {
}