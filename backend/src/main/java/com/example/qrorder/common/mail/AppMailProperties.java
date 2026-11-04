package com.example.qrorder.common.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.mail")
public record AppMailProperties(@DefaultValue("no-reply@qrorder.local") String from) {
}