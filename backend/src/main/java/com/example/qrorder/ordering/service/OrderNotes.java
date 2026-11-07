package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;

public final class OrderNotes {

    static final int MAX_LENGTH = 200;

    private OrderNotes() {}

    /** Plain text only: whitespace collapsed, control characters rejected. Blank means "no note". */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String note = raw.strip().replaceAll("\\s+", " ");
        if (note.isEmpty()) {
            return null;
        }
        if (note.length() > MAX_LENGTH) {
            throw invalid("A note can have at most " + MAX_LENGTH + " characters");
        }
        if (note.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("A note cannot contain control characters");
        }
        return note;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}