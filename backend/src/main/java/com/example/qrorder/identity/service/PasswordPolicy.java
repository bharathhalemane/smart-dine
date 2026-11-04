package com.example.qrorder.identity.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;

/** The single place that decides what a password may look like. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_BYTES = 72; // BCrypt ignores everything after 72 bytes

    private PasswordPolicy() {}

    public static void requireAcceptable(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Password must be at most " + MAX_BYTES + " bytes");
        }
    }
}