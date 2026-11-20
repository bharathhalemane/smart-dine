package com.example.qrorder.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.model.EarningsSettings;
import com.example.qrorder.earnings.model.FeeSource;
import com.example.qrorder.earnings.service.EarningsLedger;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.earnings.repository.EarningsSettingsRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class EarningsLedgerIT extends AbstractIntegrationTest {

    private static final Instant AT = Instant.parse("2026-11-10T10:00:00Z");

    @Autowired EarningsLedger ledger;
    @Autowired EarningsEntryRepository entries;
    @Autowired EarningsSettingsRepository settings;
    @Autowired HotelRepository hotels;
    @Autowired MongoTemplate mongo;

    String hotelA;
    String hotelB;

    @BeforeEach
    void seed() {
        entries.deleteAll();
        settings.deleteAll();
        hotels.deleteAll();
        Instant now = Instant.now();
        hotelA = hotels.save(new Hotel("ledger-a", "Ledger A", "x", null, "Asia/Kolkata", "INR", now)).getId();
        hotelB = hotels.save(new Hotel("ledger-b", "Ledger B", "y", null, "Asia/Kolkata", "INR", now)).getId();
        settings.save(new EarningsSettings(hotelA, 200, "seed", now)); // 2%
    }

    private EarningsEntry capture(String hotel, String payment, long gross, Long fee) {
        return ledger.recordOnlineCapture(hotel, payment, "bill-1", "session-1", "pay_" + payment, gross, fee, AT);
    }

    @Test
    void anOnlinePaymentIsSplitIntoFeeCommissionAndTheHotelsShare() {
        EarningsEntry e = capture(hotelA, "p1", 10_000, 236L);

        assertThat(e.getType()).isEqualTo(EarningsEntryType.ONLINE_PAYMENT);
        assertThat(e.getGrossMinor()).isEqualTo(10_000);
        assertThat(e.getFeeMinor()).isEqualTo(236);
        assertThat(e.getCommissionMinor()).isEqualTo(200);
        assertThat(e.getNetMinor()).isEqualTo(9_564);
        assertThat(e.getFeeSource()).isEqualTo(FeeSource.WEBHOOK);
        assertThat(e.getCommissionBps()).isEqualTo(200);
        assertThat(e.getCurrency()).isEqualTo("INR");
    }

    @Test
    void theSamePaymentIsRecordedOnlyOnce() {
        EarningsEntry first = capture(hotelA, "p1", 10_000, 236L);
        EarningsEntry second = capture(hotelA, "p1", 10_000, 236L);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(entries.count()).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesASecondEntryForTheSamePayment() {
        EarningsEntry first = capture(hotelA, "p1", 10_000, 236L);

        assertThatThrownBy(() -> entries.save(EarningsEntry.onlinePayment(hotelA, "p1", "b", "s", "pay_p1", "INR", 10_000,
                236, 200, FeeSource.WEBHOOK, 200, AT, Instant.now()))).isInstanceOf(DuplicateKeyException.class);
        assertThat(entries.count()).isEqualTo(1);
        assertThat(first.getId()).isNotNull();
    }

    @Test
    void aFeeRazorpayDidNotReportIsFlaggedAndCountedAsZero() {
        EarningsEntry e = capture(hotelA, "p1", 10_000, null);

        assertThat(e.getFeeSource()).isEqualTo(FeeSource.UNKNOWN);
        assertThat(e.getFeeMinor()).isZero();
        assertThat(e.getNetMinor()).isEqualTo(9_800);
    }

    @Test
    void aHotelWithoutItsOwnRateGetsThePlatformDefault() {
        EarningsEntry e = capture(hotelB, "p1", 10_000, 0L);

        assertThat(e.getCommissionBps()).isZero(); // app.earnings.default-commission-bps defaults to 0
        assertThat(e.getCommissionMinor()).isZero();
        assertThat(e.getNetMinor()).isEqualTo(10_000);
    }

    @Test
    void commissionNeverTakesMoreThanWhatIsLeftAfterTheFee() {
        settings.deleteAll();
        settings.save(new EarningsSettings(hotelA, 3_000, "seed", Instant.now())); // 30% of 1000 would be 300
        EarningsEntry e = capture(hotelA, "p1", 1_000, 900L);

        assertThat(e.getCommissionMinor()).isEqualTo(100); // only 100 is left after the 900 fee
        assertThat(e.getNetMinor()).isZero();
    }

    @Test
    void aRefundReversesEveryAmountAndIsRecordedOnce() {
        EarningsEntry paid = capture(hotelA, "p1", 10_000, 236L);

        Optional<EarningsEntry> refund = ledger.recordReversal(hotelA, "p1", "Guest cancelled", AT.plusSeconds(60));
        Optional<EarningsEntry> again = ledger.recordReversal(hotelA, "p1", "Guest cancelled", AT.plusSeconds(120));

        assertThat(refund).isPresent();
        EarningsEntry r = refund.get();
        String shown = "refund " + r + " | original " + paid;
        assertThat(r.getType()).as(shown).isEqualTo(EarningsEntryType.REFUND);
        assertThat(r.getGrossMinor()).as(shown).isEqualTo(-10_000);
        assertThat(r.getFeeMinor()).as(shown).isEqualTo(-236);
        assertThat(r.getCommissionMinor()).as(shown).isEqualTo(-200);
        assertThat(r.getNetMinor()).as(shown).isEqualTo(-paid.getNetMinor());
        assertThat(r.getReason()).isEqualTo("Guest cancelled");
        assertThat(again.orElseThrow().getId()).isEqualTo(r.getId());
        assertThat(entries.count()).isEqualTo(2);
    }

    @Test
    void aPaymentWithNoLedgerEntryHasNothingToReverse() {
        assertThat(ledger.recordReversal(hotelA, "cash-payment", "Void", AT)).isEmpty();
        assertThat(entries.count()).isZero();
    }

    @Test
    void anotherHotelsPaymentIsNeverTouched() {
        capture(hotelA, "p1", 10_000, 236L);

        assertThat(ledger.recordReversal(hotelB, "p1", "Wrong hotel", AT)).isEmpty();
        assertThat(entries.count()).isEqualTo(1);
    }

    @Test
    void badInputIsRefused() {
        assertThatThrownBy(() -> capture(hotelA, "p1", 0, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capture(hotelA, " ", 100, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capture(hotelA, "p1", 100, -1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capture("no-such-hotel", "p1", 100, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(entries.count()).isZero();
    }

    @Test
    void twoDifferentPaymentsOfTheSameHotelAreBothRecorded() {
        EarningsEntry first = capture(hotelA, "p1", 10_000, 236L);
        EarningsEntry second = capture(hotelA, "p2", 5_000, null);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(entries.count()).isEqualTo(2);
    }

    @Test
    void theUniqueIndexIsOnTheUniqueKeyFieldAlone() {
        assertThat(mongo.indexOps(EarningsEntry.class).getIndexInfo()).anySatisfy(info -> {
            assertThat(info.getName()).isEqualTo("earnings_unique_key");
            assertThat(info.isUnique()).isTrue();
            assertThat(info.isSparse()).isTrue();
            assertThat(info.getIndexFields()).extracting(IndexField::getKey).containsExactly("uniqueKey");
        });
    }

    @Test
    void theTimeIndexCoversHotelAndTime() {
        assertThat(mongo.indexOps(EarningsEntry.class).getIndexInfo()).anySatisfy(info -> {
            assertThat(info.getName()).isEqualTo("earnings_hotel_time");
            assertThat(info.getIndexFields()).extracting(IndexField::getKey).containsExactly("hotelId", "occurredAt");
        });
    }
}