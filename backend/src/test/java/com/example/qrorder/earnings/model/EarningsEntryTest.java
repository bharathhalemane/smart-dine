package com.example.qrorder.earnings.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EarningsEntryTest {

    private static final Instant AT = Instant.parse("2026-11-10T10:00:00Z");

    private static EarningsEntry paid() {
        return EarningsEntry.onlinePayment("h1", "p1", "b1", "s1", "pay_1", "INR", 10_000, 236, 200,
                FeeSource.WEBHOOK, 200, AT, AT);
    }

    @Test
    void aReversalIsTheExactNegativeOfThePayment() {
        EarningsEntry original = paid();
        EarningsEntry r = EarningsEntry.reversalOf(original, "Guest cancelled", AT.plusSeconds(60), AT);

        assertThat(original.getNetMinor()).isEqualTo(9_564);
        assertThat(r.getType()).isEqualTo(EarningsEntryType.REFUND);
        assertThat(r.getGrossMinor()).isEqualTo(-10_000);
        assertThat(r.getFeeMinor()).isEqualTo(-236);
        assertThat(r.getCommissionMinor()).isEqualTo(-200);
        assertThat(r.getNetMinor()).isEqualTo(-9_564);
        assertThat(r.getPaymentId()).isEqualTo("p1");
        assertThat(r.getReason()).isEqualTo("Guest cancelled");
        assertThat(original.getGrossMinor()).as("the original is untouched").isEqualTo(10_000);
    }

    @Test
    void aPayoutReducesWhatIsOwed() {
        EarningsEntry payout = EarningsEntry.payout("h1", "po1", 3_000, "INR", "Weekly", AT, AT);

        assertThat(payout.getType()).isEqualTo(EarningsEntryType.PAYOUT);
        assertThat(payout.getNetMinor()).isEqualTo(-3_000);
        assertThat(payout.getGrossMinor()).isZero();
        assertThatThrownBy(() -> EarningsEntry.payout("h1", "po1", 0, "INR", "x", AT, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EarningsEntry.payout("h1", " ", 100, "INR", "x", AT, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theUniqueKeyDiffersByTypeHotelAndReference() {
        EarningsEntry original = paid();
        EarningsEntry refund = EarningsEntry.reversalOf(original, "r", AT, AT);

        assertThat(original.getUniqueKey()).isEqualTo("ONLINE_PAYMENT:h1:p1");
        assertThat(refund.getUniqueKey()).isEqualTo("REFUND:h1:p1");
        assertThat(EarningsEntry.keyOf(EarningsEntryType.ONLINE_PAYMENT, "h2", "p1")).isNotEqualTo(original.getUniqueKey());
        assertThat(EarningsEntry.keyOf(EarningsEntryType.ONLINE_PAYMENT, "h1", "p2")).isNotEqualTo(original.getUniqueKey());
    }
}