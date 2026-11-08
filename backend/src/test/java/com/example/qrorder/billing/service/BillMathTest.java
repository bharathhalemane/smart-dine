package com.example.qrorder.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.billing.model.BillDiscount;
import com.example.qrorder.billing.model.DiscountType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BillMathTest {

    private static BillDiscount percent(long bps) {
        return new BillDiscount("d", DiscountType.PERCENT, bps, "reason", "u", Instant.now());
    }

    private static BillDiscount flat(long paise) {
        return new BillDiscount("d", DiscountType.FLAT, paise, "reason", "u", Instant.now());
    }

    @Test
    void nothingConfiguredMeansTheTotalIsTheSubtotal() {
        BillMath.Breakdown b = BillMath.compute(100_000, List.of(), 0, false, 0);

        assertThat(b.discountMinor()).isZero();
        assertThat(b.serviceChargeMinor()).isZero();
        assertThat(b.taxMinor()).isZero();
        assertThat(b.totalMinor()).isEqualTo(100_000);
    }

    @Test
    void exclusiveTaxIsAddedOnTop() {
        BillMath.Breakdown b = BillMath.compute(100_000, List.of(), 500, false, 0);

        assertThat(b.taxMinor()).isEqualTo(5_000);
        assertThat(b.totalMinor()).isEqualTo(105_000);
    }

    @Test
    void theServiceChargeIsAddedAndTaxedToo() {
        BillMath.Breakdown b = BillMath.compute(100_000, List.of(), 500, false, 1000);

        assertThat(b.serviceChargeMinor()).isEqualTo(10_000);
        assertThat(b.taxMinor()).isEqualTo(5_500); // 5% of 110,000
        assertThat(b.totalMinor()).isEqualTo(115_500);
    }

    @Test
    void inclusiveTaxIsExtractedAndTheTotalDoesNotGrow() {
        BillMath.Breakdown b = BillMath.compute(100_000, List.of(), 500, true, 0);

        assertThat(b.taxMinor()).isEqualTo(4_762); // 100,000 x 5 / 105
        assertThat(b.totalMinor()).isEqualTo(100_000);

        assertThat(BillMath.compute(105_000, List.of(), 500, true, 0).taxMinor()).isEqualTo(5_000);
    }

    @Test
    void inclusiveTaxAlsoSitsInsideTheServiceCharge() {
        BillMath.Breakdown b = BillMath.compute(100_000, List.of(), 500, true, 1000);

        assertThat(b.serviceChargeMinor()).isEqualTo(10_000);
        assertThat(b.taxMinor()).isEqualTo(5_238); // 110,000 x 5 / 105
        assertThat(b.totalMinor()).isEqualTo(110_000);
    }

    @Test
    void discountsComeOffBeforeServiceChargeAndTax() {
        BillMath.Breakdown b = BillMath.compute(100_000, List.of(percent(1000)), 500, false, 1000);

        assertThat(b.discountMinor()).isEqualTo(10_000);
        assertThat(b.serviceChargeMinor()).isEqualTo(9_000);
        assertThat(b.taxMinor()).isEqualTo(4_950);
        assertThat(b.totalMinor()).isEqualTo(103_950);
        assertThat(b.discountAmounts()).containsExactly(10_000L);
    }

    @Test
    void severalDiscountsAreAppliedInOrderAndNeverTakeMoreThanTheSubtotal() {
        BillMath.Breakdown few = BillMath.compute(102_000, List.of(percent(1000), flat(1_800)), 0, false, 0);
        assertThat(few.discountAmounts()).containsExactly(10_200L, 1_800L);
        assertThat(few.totalMinor()).isEqualTo(90_000);

        BillMath.Breakdown capped = BillMath.compute(5_000, List.of(flat(4_000), flat(4_000)), 500, false, 1000);
        assertThat(capped.discountAmounts()).containsExactly(4_000L, 1_000L);
        assertThat(capped.discountMinor()).isEqualTo(5_000);
        assertThat(capped.totalMinor()).isZero();
    }

    @Test
    void aFullPercentageDiscountIsAComp() {
        BillMath.Breakdown b = BillMath.compute(100_000, List.of(percent(10_000)), 500, false, 1000);

        assertThat(b.discountMinor()).isEqualTo(100_000);
        assertThat(b.totalMinor()).isZero();
    }

    @Test
    void roundingIsHalfUp() {
        assertThat(BillMath.compute(1_050, List.of(), 500, false, 0).taxMinor()).isEqualTo(53); // 52.5
        assertThat(BillMath.compute(1_049, List.of(), 500, false, 0).taxMinor()).isEqualTo(52); // 52.45
        assertThat(BillMath.compute(1_000, List.of(percent(3333)), 0, false, 0).discountMinor()).isEqualTo(333);
    }

    @Test
    void anEmptyBillStaysAtZero() {
        BillMath.Breakdown b = BillMath.compute(0, List.of(percent(1000)), 500, false, 1000);

        assertThat(b.totalMinor()).isZero();
        assertThat(b.taxMinor()).isZero();
        assertThat(b.discountAmounts()).containsExactly(0L);
    }
}