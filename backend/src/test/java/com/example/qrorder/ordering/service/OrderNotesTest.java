package com.example.qrorder.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.common.error.DomainException;
import org.junit.jupiter.api.Test;

class OrderNotesTest {

    @Test
    void notesAreTrimmedAndWhitespaceIsCollapsed() {
        assertThat(OrderNotes.clean("  no   onion,\n less spicy ")).isEqualTo("no onion, less spicy");
        assertThat(OrderNotes.clean("less spicy < 5 please")).isEqualTo("less spicy < 5 please");
    }

    @Test
    void blankMeansNoNote() {
        assertThat(OrderNotes.clean(null)).isNull();
        assertThat(OrderNotes.clean("  \n ")).isNull();
    }

    @Test
    void controlCharactersAreRejected() {
        assertThatThrownBy(() -> OrderNotes.clean("no onion\u0007")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrderNotes.clean("a\u0000b")).isInstanceOf(DomainException.class);
    }

    @Test
    void overlongNotesAreRejected() {
        assertThat(OrderNotes.clean("x".repeat(200))).hasSize(200);
        assertThatThrownBy(() -> OrderNotes.clean("x".repeat(201))).isInstanceOf(DomainException.class);
    }
}