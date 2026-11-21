package com.example.qrorder.billing.service;

import com.example.qrorder.billing.event.BillChanged;
import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentStatus;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.earnings.service.EarningsLedger;
/**
 * The only place money moves on a bill. A separate bean so the transaction boundary is a real proxy call.
 * The bill's running paid counter and the ledger entry commit together or not at all.
 */
@Service
public class PaymentWriter {

    /** The bill changed between the caller's read and this write. The caller reads again. */
    public static class StaleBillException extends RuntimeException {
        public StaleBillException() {
            super("The bill changed", null, false, false);
        }
    }

    private final MongoTemplate mongo;
    private final ApplicationEventPublisher events;
    private final EarningsLedger ledger;

    public PaymentWriter(MongoTemplate mongo, ApplicationEventPublisher events, EarningsLedger ledger) {
        this.mongo = mongo;
        this.events = events;
        this.ledger = ledger;
    }

    /**
     * Counts the payment on the bill only if the bill still says expectedPaid was paid AND its total still
     * covers this amount, then writes the ledger entry. Two simultaneous payments can never both fit.
     */
    /** Cash, UPI and card taken by staff. These never touch the platform's gateway, so they are not in the ledger. */
    /** Cash, UPI, card, and online payments made with the hotel's own keys: none of these are in the platform ledger. */
    @Transactional
    public Payment record(Payment payment, String billId, long expectedPaid) {
        return record(payment, billId, expectedPaid, false, null);
    }

    /**
     * Same as above. When viaPlatform is true (an online payment into the platform's Razorpay account) the earnings entry
     * is written in the same transaction, so the bill, the payment and the hotel's earnings commit together or not at all.
     *
     * @param gatewayFeeMinor the fee Razorpay reported for this payment, or null if it reported none
     */
    @Transactional
    public Payment record(Payment payment, String billId, long expectedPaid, boolean viaPlatform, Long gatewayFeeMinor) {
        long amount = payment.getAmountMinor();
        Criteria paidIs = expectedPaid == 0
                ? new Criteria().orOperator(Criteria.where("paidMinor").is(0L), Criteria.where("paidMinor").exists(false))
                : Criteria.where("paidMinor").is(expectedPaid);
        Query query = Query.query(Criteria.where("id").is(billId).and("hotelId").is(payment.getHotelId())
                .and("totalMinor").gte(expectedPaid + amount).andOperator(paidIs));

        Bill updated = mongo.findAndModify(query, new Update().inc("paidMinor", amount),
                FindAndModifyOptions.options().returnNew(true), Bill.class);
        if (updated == null) {
            throw new StaleBillException();
        }
        Payment saved = mongo.insert(payment);
        if (viaPlatform && saved.getMethod() == PaymentMethod.ONLINE) {
            ledger.recordOnlineCapture(saved.getHotelId(), saved.getId(), saved.getBillId(), saved.getDiningSessionId(),
                    saved.getReference(), saved.getAmountMinor(), gatewayFeeMinor, saved.getReceivedAt());
        }
        events.publishEvent(new BillChanged(saved.getHotelId(), saved.getDiningSessionId(), "PAYMENT_RECORDED"));
        return saved;
    }

    /** RECORDED to VOIDED, and the amount comes off the bill's paid counter, in one transaction. Null if not RECORDED. */
    @Transactional
    public Payment voidPayment(String hotelId, String paymentId, String userId, String reason, Instant now) {
        Payment voided = mongo.findAndModify(
                Query.query(Criteria.where("id").is(paymentId).and("hotelId").is(hotelId)
                        .and("status").is(PaymentStatus.RECORDED)),
                new Update().set("status", PaymentStatus.VOIDED).set("voidedByUserId", userId)
                        .set("voidedAt", now).set("voidReason", reason),
                FindAndModifyOptions.options().returnNew(true), Payment.class);
        if (voided == null) {
            return null;
        }
        Bill bill = mongo.findAndModify(
                Query.query(Criteria.where("id").is(voided.getBillId()).and("hotelId").is(hotelId)
                        .and("paidMinor").gte(voided.getAmountMinor())),
                new Update().inc("paidMinor", -voided.getAmountMinor()),
                FindAndModifyOptions.options().returnNew(true), Bill.class);
        if (bill == null) {
            throw new IllegalStateException("The bill's paid total is lower than a recorded payment: " + paymentId);
        }
        if (voided.getMethod() == PaymentMethod.ONLINE) {
            // a refunded online payment: the hotel gives back its share (the ledger ignores anything that was never online)
            ledger.recordReversal(hotelId, voided.getId(), reason, now);
        }
        events.publishEvent(new BillChanged(voided.getHotelId(), voided.getDiningSessionId(), "PAYMENT_VOIDED"));
        return voided;
    }
}