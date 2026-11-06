package com.example.qrorder.tables.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.qr")
public record QrProperties(@DefaultValue("http://localhost:5173") String customerBaseUrl) {
}