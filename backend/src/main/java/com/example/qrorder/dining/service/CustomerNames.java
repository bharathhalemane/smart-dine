package com.example.qrorder.dining.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import java.util.regex.Pattern;

public final class CustomerNames {

    static final int MAX_LENGTH = 30;
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{M}\\p{N} .'\\-]+");

    private CustomerNames() {}

    /** Trims, collapses inner whitespace. Returns null for blank (meaning "no name"). Throws 400 for bad names. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > MAX_LENGTH) {
            throw invalid("A name can have at most " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(name).matches()) {
            throw invalid("A name can only contain letters, numbers, spaces and . ' -");
        }
        return name;
    }

    public static String label(int number, String displayName) {
        return displayName == null || displayName.isBlank() ? "Guest " + number : displayName;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}