package com.example.qrorder.tables.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableNumbersTest {

    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(TableNumbers.normalize("  t   1 ")).isEqualTo("T 1");
        assertThat(TableNumbers.normalize("terrace-3")).isEqualTo("TERRACE-3");
    }

    @Test
    void sortsNumbersNaturally() {
        List<String> numbers = new ArrayList<>(List.of("T10", "T2", "T1", "T21", "T3"));
        numbers.sort(TableNumbers::compare);
        assertThat(numbers).containsExactly("T1", "T2", "T3", "T10", "T21");
    }

    @Test
    void groupsByPrefixThenNumberAndPutsPlainNamesFirst() {
        List<String> numbers = new ArrayList<>(List.of("B1", "A10", "A2", "BAR", "7"));
        numbers.sort(TableNumbers::compare);
        assertThat(numbers).containsExactly("7", "A2", "A10", "B1", "BAR");
    }
}