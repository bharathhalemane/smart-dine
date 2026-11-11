package com.example.qrorder.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageSnifferTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'};

    @Test
    void realImagesMatchTheirOwnType() {
        assertThat(ImageSniffer.matches("image/jpeg", JPEG)).isTrue();
        assertThat(ImageSniffer.matches("image/png", PNG)).isTrue();
        assertThat(ImageSniffer.matches("image/webp", WEBP)).isTrue();
        assertThat(ImageSniffer.matches("IMAGE/JPEG", JPEG)).isTrue();
    }

    @Test
    void anImageDeclaredAsAnotherTypeDoesNotMatch() {
        assertThat(ImageSniffer.matches("image/png", JPEG)).isFalse();
        assertThat(ImageSniffer.matches("image/jpeg", WEBP)).isFalse();
        assertThat(ImageSniffer.matches("image/webp", PNG)).isFalse();
    }

    @Test
    void htmlOrTextPretendingToBeAnImageDoesNotMatch() {
        byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        for (String type : new String[] {"image/jpeg", "image/png", "image/webp"}) {
            assertThat(ImageSniffer.matches(type, html)).as(type).isFalse();
        }
    }

    @Test
    void shortOrMissingDataAndUnknownTypesDoNotMatch() {
        assertThat(ImageSniffer.matches("image/jpeg", new byte[] {(byte) 0xFF})).isFalse();
        assertThat(ImageSniffer.matches("image/jpeg", null)).isFalse();
        assertThat(ImageSniffer.matches(null, JPEG)).isFalse();
        assertThat(ImageSniffer.matches("image/svg+xml", JPEG)).isFalse();
        assertThat(ImageSniffer.matches("image/webp", new byte[] {'R', 'I', 'F', 'F'})).isFalse();
    }
}