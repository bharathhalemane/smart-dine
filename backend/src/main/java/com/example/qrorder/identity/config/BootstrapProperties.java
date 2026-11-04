package com.example.qrorder.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapProperties(
        @DefaultValue("") String adminEmail,
        @DefaultValue("") String adminPassword,
        @DefaultValue("Platform Admin") String adminName) {
}