package com.example.qrorder.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    private static Money inr(long minor) {
        return Money.ofMinor(minor, "INR");
    }

    @Test
    void addsAndSubtractsInMinorUnits() {
        assertThat(inr(1050).plus(inr(250))).isEqualTo(inr(1300));
        assertThat(inr(1050).minus(inr(250))).isEqualTo(inr(800));
    }

    @Test
    void multipliesByQuantity() {
        assertThat(inr(24999).times(3)).isEqualTo(inr(74997));
    }

    @Test
    void percentRoundsHalfUp() {
        // 5% of 1010 paise = 50.5 -> 51
        assertThat(inr(1010).percentOf(500)).isEqualTo(inr(51));
        // 5% of 1009 paise = 50.45 -> 50
        assertThat(inr(1009).percentOf(500)).isEqualTo(inr(50));
    }

    @Test
    void rejectsCurrencyMismatch() {
        assertThatThrownBy(() -> inr(100).plus(Money.ofMinor(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowIsDetectedNotWrapped() {
        assertThatThrownBy(() -> inr(Long.MAX_VALUE).plus(inr(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void noFloatingPointDrift() {
        Money total = Money.zero("INR");
        for (int i = 0; i < 10; i++) {
            total = total.plus(inr(10));
        }
        assertThat(total).isEqualTo(inr(100));
    }
}