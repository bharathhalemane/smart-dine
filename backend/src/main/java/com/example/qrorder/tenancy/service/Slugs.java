package com.example.qrorder.tenancy.service;

import java.text.Normalizer;
import java.util.Locale;

final class Slugs {

    private static final int MAX_LENGTH = 40;

    private Slugs() {}

    static String slugify(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "hotel" : slug;
    }
}