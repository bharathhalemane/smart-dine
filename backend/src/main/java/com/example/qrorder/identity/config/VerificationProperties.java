package com.example.qrorder.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.verification")
public record VerificationProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("60s") Duration resendCooldown,
        @DefaultValue("http://localhost:5173") String linkBaseUrl) {
}