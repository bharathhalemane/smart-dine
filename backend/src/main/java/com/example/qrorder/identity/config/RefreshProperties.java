package com.example.qrorder.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.refresh")
public record RefreshProperties(
        @DefaultValue("7d") Duration ttl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("Strict") String cookieSameSite) {
}