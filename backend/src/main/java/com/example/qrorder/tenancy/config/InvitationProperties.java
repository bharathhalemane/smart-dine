package com.example.qrorder.tenancy.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.invitation")
public record InvitationProperties(
        @DefaultValue("7d") Duration ttl,
        @DefaultValue("http://localhost:5173") String linkBaseUrl) {
}