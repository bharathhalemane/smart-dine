package com.example.qrorder.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.ordering.dto.CartLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderRequestHashTest {

    private static CartLine line(String item, String variant, List<String> options, int qty, String note) {
        return new CartLine(item, variant, options, qty, note);
    }

    private static final CartLine PIZZA = line("pizza", "half", List.of("cheese", "jalapeno"), 1, "extra crispy");

    @Test
    void theSameCartAlwaysHashesTheSame() {
        assertThat(OrderRequestHash.of(List.of(PIZZA))).isEqualTo(OrderRequestHash.of(List.of(PIZZA)));
    }

    @Test
    void addOnOrderAndNoteWhitespaceDoNotMatter() {
        CartLine same = line("pizza", "half", List.of("jalapeno", "cheese"), 1, "  extra   crispy ");
        assertThat(OrderRequestHash.of(List.of(same))).isEqualTo(OrderRequestHash.of(List.of(PIZZA)));
    }

    @Test
    void anyRealChangeChangesTheHash() {
        String base = OrderRequestHash.of(List.of(PIZZA));
        assertThat(OrderRequestHash.of(List.of(line("pizza", "full", List.of("cheese", "jalapeno"), 1, "extra crispy"))))
                .isNotEqualTo(base);
        assertThat(OrderRequestHash.of(List.of(line("pizza", "half", List.of("cheese"), 1, "extra crispy"))))
                .isNotEqualTo(base);
        assertThat(OrderRequestHash.of(List.of(line("pizza", "half", List.of("cheese", "jalapeno"), 2, "extra crispy"))))
                .isNotEqualTo(base);
        assertThat(OrderRequestHash.of(List.of(line("pizza", "half", List.of("cheese", "jalapeno"), 1, null))))
                .isNotEqualTo(base);
        assertThat(OrderRequestHash.of(List.of(line("soup", "half", List.of("cheese", "jalapeno"), 1, "extra crispy"))))
                .isNotEqualTo(base);
    }

    @Test
    void lineOrderAndExtraLinesMatter() {
        CartLine soup = line("soup", null, null, 1, null);
        assertThat(OrderRequestHash.of(List.of(PIZZA, soup))).isNotEqualTo(OrderRequestHash.of(List.of(soup, PIZZA)));
        assertThat(OrderRequestHash.of(List.of(PIZZA, soup))).isNotEqualTo(OrderRequestHash.of(List.of(PIZZA)));
    }

    @Test
    void valuesCannotBeCraftedToBlurFieldBoundaries() {
        CartLine a = line("ab", "c", null, 1, null);
        CartLine b = line("a", "bc", null, 1, null);
        assertThat(OrderRequestHash.of(List.of(a))).isNotEqualTo(OrderRequestHash.of(List.of(b)));
    }
}