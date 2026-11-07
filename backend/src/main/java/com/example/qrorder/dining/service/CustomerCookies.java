package com.example.qrorder.dining.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Builds the customer cookie: HttpOnly so page scripts cannot read it, scoped to the API only. */
@Component
public class CustomerCookies {

    public static final String NAME = "qr_customer";

    private final boolean secure;
    private final String sameSite;
    private final long maxAgeSeconds;

    public CustomerCookies(@Value("${app.customer.cookie-secure:true}") boolean secure,
                           @Value("${app.customer.cookie-same-site:Lax}") String sameSite,
                           @Value("${app.customer.cookie-max-age-seconds:43200}") long maxAgeSeconds) {
        this.secure = secure;
        this.sameSite = sameSite;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public ResponseCookie issue(String token) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api/v1")
                .maxAge(maxAgeSeconds)
                .build();
    }
}