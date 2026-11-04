package com.example.qrorder.tenancy.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugsTest {

    @Test
    void lowercasesAndHyphenates() {
        assertThat(Slugs.slugify("Spice Garden")).isEqualTo("spice-garden");
    }

    @Test
    void stripsAccentsAndPunctuation() {
        assertThat(Slugs.slugify("  Caf\u00e9 M\u00fcller!! & Sons  ")).isEqualTo("cafe-muller-sons");
    }

    @Test
    void fallsBackWhenNothingUsable() {
        assertThat(Slugs.slugify("!!!")).isEqualTo("hotel");
        assertThat(Slugs.slugify("   ")).isEqualTo("hotel");
    }

    @Test
    void truncatesLongNamesWithoutATrailingHyphen() {
        String slug = Slugs.slugify("a".repeat(39) + " bbbb");

        assertThat(slug.length()).isLessThanOrEqualTo(40);
        assertThat(slug).doesNotEndWith("-");
    }
}