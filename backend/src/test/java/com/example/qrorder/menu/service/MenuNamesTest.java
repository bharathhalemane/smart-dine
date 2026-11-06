package com.example.qrorder.menu.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MenuNamesTest {

    @Test
    void normalizeTrimsAndCollapsesWhitespaceButKeepsCase() {
        assertThat(MenuNames.normalize("  Main   Course ")).isEqualTo("Main Course");
    }

    @Test
    void keyIgnoresCaseSoDuplicatesAreCaughtWhateverTheSpelling() {
        assertThat(MenuNames.key("Main Course")).isEqualTo(MenuNames.key("MAIN course"));
        assertThat(MenuNames.key("Starters")).isNotEqualTo(MenuNames.key("Starter"));
    }
}