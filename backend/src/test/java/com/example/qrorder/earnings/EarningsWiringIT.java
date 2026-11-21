package com.example.qrorder.earnings;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.service.PaymentWriter;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.model.FeeSource;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The writer is the only place money moves, so this proves the ledger follows it. Uses the bill the other IT classes use. */
class EarningsWiringIT extends AbstractIntegrationTest {

    @Autowired PaymentWriter writer;
    @Autowired EarningsEntryRepository entries;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        entries.deleteAll();
    }

    private Payment online(String hotelId, String sessionId, String billId, long amount, String key) {
        return new Payment(hotelId, sessionId, billId, PaymentMethod.ONLINE, amount, "pay_" + key, "Online payment",
                "Guest", "online:razorpay", Instant.now(), key, "online");
    }

    private Payment cash(String hotelId, String sessionId, String billId, long amount, String key) {
        return new Payment(hotelId, sessionId, billId, PaymentMethod.CASH, amount, null, null, "Guest", "staff-1",
                Instant.now(), key, "cash");
    }

    @Test
    void anOnlinePaymentWritesItsEarningsEntryWithTheWebhookFee() {
        Fixture f = Fixture.create(mongo, 10_000);

        Payment saved = writer.record(online(f.hotelId, f.sessionId, f.billId, 10_000, "k1"), f.billId, 0, 236L);

        List<EarningsEntry> all = entries.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getType()).isEqualTo(EarningsEntryType.ONLINE_PAYMENT);
        assertThat(all.get(0).getPaymentId()).isEqualTo(saved.getId());
        assertThat(all.get(0).getFeeMinor()).isEqualTo(236);
        assertThat(all.get(0).getFeeSource()).isEqualTo(FeeSource.WEBHOOK);
    }

    @Test
    void aMissingFeeIsFlaggedUnknown() {
        Fixture f = Fixture.create(mongo, 10_000);

        writer.record(online(f.hotelId, f.sessionId, f.billId, 10_000, "k1"), f.billId, 0);

        assertThat(entries.findAll().get(0).getFeeSource()).isEqualTo(FeeSource.UNKNOWN);
    }

    @Test
    void cashIsNeverInTheLedger() {
        Fixture f = Fixture.create(mongo, 10_000);

        writer.record(cash(f.hotelId, f.sessionId, f.billId, 10_000, "k1"), f.billId, 0);

        assertThat(entries.count()).isZero();
    }

    @Test
    void voidingAnOnlinePaymentReversesItOnce() {
        Fixture f = Fixture.create(mongo, 10_000);
        Payment saved = writer.record(online(f.hotelId, f.sessionId, f.billId, 10_000, "k1"), f.billId, 0, 236L);

        writer.voidPayment(f.hotelId, saved.getId(), "user-1", "Guest cancelled", Instant.now());
        Payment again = writer.voidPayment(f.hotelId, saved.getId(), "user-1", "Guest cancelled", Instant.now());

        assertThat(again).isNull(); // already voided: nothing more happens
        assertThat(entries.count()).isEqualTo(2);
        EarningsEntry refund = mongo.findOne(Query.query(Criteria.where("type").is(EarningsEntryType.REFUND)),
                EarningsEntry.class);
        assertThat(refund.getNetMinor()).isLessThan(0);
    }

    @Test
    void voidingCashWritesNothingToTheLedger() {
        Fixture f = Fixture.create(mongo, 10_000);
        Payment saved = writer.record(cash(f.hotelId, f.sessionId, f.billId, 10_000, "k1"), f.billId, 0);

        writer.voidPayment(f.hotelId, saved.getId(), "user-1", "Typo", Instant.now());

        assertThat(entries.count()).isZero();
    }

    @Test
    void ifTheBillIsStaleNoEarningsEntryIsLeftBehind() {
        Fixture f = Fixture.create(mongo, 10_000);

        try {
            writer.record(online(f.hotelId, f.sessionId, f.billId, 10_000, "k1"), f.billId, 5_000, 0L); // wrong expectedPaid
        } catch (PaymentWriter.StaleBillException expected) {
            // fine
        }

        assertThat(entries.count()).isZero();
    }
}