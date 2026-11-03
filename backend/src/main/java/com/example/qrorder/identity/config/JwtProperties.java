package com.example.qrorder.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, @DefaultValue("qr-order") String issuer, @DefaultValue("15m") Duration accessTokenTtl){

}

