package com.example.qrorder.menu.service;

import java.util.Locale;

final class MenuNames {

    private MenuNames() {}

    /** "  Main   Course " becomes "Main Course". Case is kept for display. */
    static String normalize(String raw) {
        return raw.trim().replaceAll("\\s+", " ");
    }

    /** What uniqueness is judged on: "Main Course" and "main course" are the same category. */
    static String key(String normalizedName) {
        return normalizedName.toLowerCase(Locale.ROOT);
    }
}