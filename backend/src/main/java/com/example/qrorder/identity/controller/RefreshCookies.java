package com.example.qrorder.identity.controller;

import com.example.qrorder.identity.config.RefreshProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookies {

    public static final String NAME = "refresh_token";
    private static final String PATH = "/api/v1/auth"; // browser sends it only to auth endpoints

    private final RefreshProperties props;

    public RefreshCookies(RefreshProperties props) {
        this.props = props;
    }

    public ResponseCookie issue(String token) {
        return base(token).maxAge(props.ttl()).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite(props.cookieSameSite())
                .path(PATH);
    }
}