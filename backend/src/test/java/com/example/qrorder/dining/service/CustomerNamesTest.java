package com.example.qrorder.dining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.common.error.DomainException;
import org.junit.jupiter.api.Test;

class CustomerNamesTest {

    @Test
    void namesAreTrimmedAndWhitespaceIsCollapsed() {
        assertThat(CustomerNames.clean("  Anita   Rao ")).isEqualTo("Anita Rao");
        assertThat(CustomerNames.clean("O'Neil-Smith Jr.")).isEqualTo("O'Neil-Smith Jr.");
    }

    @Test
    void blankMeansNoName() {
        assertThat(CustomerNames.clean(null)).isNull();
        assertThat(CustomerNames.clean("   ")).isNull();
        assertThat(CustomerNames.clean("")).isNull();
    }

    @Test
    void otherScriptsAreAllowed() {
        assertThat(CustomerNames.clean("ಭರತ್")).isEqualTo("ಭರತ್");
        assertThat(CustomerNames.clean("José 2")).isEqualTo("José 2");
        assertThat(CustomerNames.clean("भारत कुमार")).isEqualTo("भारत कुमार");
        assertThat(CustomerNames.clean("Jose\u0301")).isEqualTo("Jose\u0301"); // e + combining acute
    }

    @Test
    void unsafeOrTooLongNamesAreRejected() {
        for (String bad : new String[] {"<script>alert(1)</script>", "Bob; DROP", "Smile \uD83D\uDE00", "a\u0000b",
                "x".repeat(31)}) {
            assertThatThrownBy(() -> CustomerNames.clean(bad)).as(bad).isInstanceOf(DomainException.class);
        }
        assertThat(CustomerNames.clean("x".repeat(30))).hasSize(30);
    }

    @Test
    void theLabelFallsBackToGuestNumber() {
        assertThat(CustomerNames.label(3, null)).isEqualTo("Guest 3");
        assertThat(CustomerNames.label(3, "Anita")).isEqualTo("Anita");
    }
}