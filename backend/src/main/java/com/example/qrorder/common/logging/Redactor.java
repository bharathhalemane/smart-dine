package com.example.qrorder.common.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks secrets and personal data in text that is about to be logged. Pure function, safe to call anywhere. */
public final class Redactor {

    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://[^/\\s:@]+:)[^@\\s/]+@");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(\"?[A-Za-z_.-]*(?:password|passwd|secret|token|apikey|api_key|authorization|cookie|qr_customer)\"?"
                    + "\\s*[:=]\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s,;&}\\]]+)");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
    private static final Pattern HEX_64 = Pattern.compile("\\b[0-9a-fA-F]{64}\\b");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9_-]{43,}\\b");
    private static final Pattern EMAIL = Pattern.compile(
            "([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private Redactor() {}

    public static String redact(String text) {
        if (text == null || text.length() < 6) {
            return text;
        }
        String out = JWT.matcher(text).replaceAll("[jwt]");
        out = BEARER.matcher(out).replaceAll("$1[redacted]");
        out = URL_CREDENTIALS.matcher(out).replaceAll("$1[redacted]@");
        out = SECRET_FIELD.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1)) + "[redacted]");
        out = BCRYPT.matcher(out).replaceAll("[hash]");
        out = HEX_64.matcher(out).replaceAll("[hex]");
        out = LONG_TOKEN.matcher(out).replaceAll("[token]");
        out = EMAIL.matcher(out).replaceAll("$1***@$2");
        return out;
    }
}