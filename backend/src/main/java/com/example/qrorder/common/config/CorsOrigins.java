package com.example.qrorder.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads app.cors.allowed-origins and refuses a badly written list at startup instead of failing quietly later. */
public final class CorsOrigins {

    private static final Logger log = LoggerFactory.getLogger(CorsOrigins.class);
    private static final Pattern ORIGIN = Pattern.compile("^https?://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\\d{1,5})?$");
    private static final Pattern LOCAL = Pattern.compile("^http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?$");

    private CorsOrigins() {}

    /** Comma-separated exact origins such as https://app.example.com: no *, no path, no trailing slash. */
    public static List<String> parse(String raw) {
        List<String> origins = new ArrayList<>();
        if (raw == null) {
            return origins;
        }
        for (String part : raw.split(",")) {
            String origin = part.trim().toLowerCase(Locale.ROOT);
            if (origin.isEmpty()) {
                continue;
            }
            if (!ORIGIN.matcher(origin).matches()) {
                throw new IllegalStateException("app.cors.allowed-origins has an invalid entry '" + part.trim()
                        + "'. Use exact origins like https://app.example.com (no *, no path, no trailing slash)");
            }
            if (origin.startsWith("http://") && !LOCAL.matcher(origin).matches()) {
                log.warn("CORS origin {} is plain http: browsers will send cookies and tokens unencrypted", origin);
            }
            if (!origins.contains(origin)) {
                origins.add(origin);
            }
        }
        return List.copyOf(origins);
    }
}