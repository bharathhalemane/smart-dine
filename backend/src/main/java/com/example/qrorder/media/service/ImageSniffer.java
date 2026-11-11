package com.example.qrorder.media.service;

import java.util.Locale;

/** Checks that a file's first bytes match the image type it claims. Declared types alone prove nothing. */
public final class ImageSniffer {

    public static final int BYTES_NEEDED = 12;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private ImageSniffer() {}

    public static boolean matches(String contentType, byte[] head) {
        if (contentType == null || head == null) {
            return false;
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                    && (head[2] & 0xFF) == 0xFF;
            case "image/png" -> startsWith(head, PNG);
            case "image/webp" -> head.length >= 12 && startsWith(head, new byte[] {'R', 'I', 'F', 'F'})
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}