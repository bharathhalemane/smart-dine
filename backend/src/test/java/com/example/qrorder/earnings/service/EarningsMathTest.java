package com.example.qrorder.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EarningsMathTest {

    @Test
    void takesBasisPointsOfAnAmount() {
        assertThat(EarningsMath.bps(10_000, 200)).isEqualTo(200);   // 2% of 100.00
        assertThat(EarningsMath.bps(10_000, 0)).isZero();
        assertThat(EarningsMath.bps(0, 300)).isZero();
        assertThat(EarningsMath.bps(1_000_000_000_000L, 300)).isEqualTo(30_000_000_000L);
    }

    @Test
    void roundsHalfUp() {
        assertThat(EarningsMath.bps(999, 250)).isEqualTo(25);  // 24.975
        assertThat(EarningsMath.bps(1, 5000)).isEqualTo(1);    // 0.5
        assertThat(EarningsMath.bps(1, 4999)).isZero();        // 0.4999
        assertThat(EarningsMath.bps(3, 3333)).isEqualTo(1);    // 0.9999
    }

    @Test
    void refusesNegativeInput() {
        assertThatThrownBy(() -> EarningsMath.bps(-1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EarningsMath.bps(100, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}