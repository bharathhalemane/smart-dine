package com.example.qrorder.earnings.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One line of a hotel's earnings ledger. Never edited or deleted. netMinor is signed: positive adds to what the platform
 * owes the hotel, negative reduces it. A balance is the sum of netMinor.
 */
@Document("earningsEntries")
@CompoundIndex(name = "earnings_hotel_time", def = "{'hotelId': 1, 'occurredAt': -1}")
public class EarningsEntry {

    @Id
    private String id;

    /**
     * "TYPE:hotelId:reference". Gives one ONLINE_PAYMENT and at most one REFUND per payment, and one PAYOUT per payout.
     * A single-field sparse unique index, so entries without a key are simply not indexed.
     */
    @Indexed(name = "earnings_unique_key", unique = true, sparse = true)
    private String uniqueKey;

    private String hotelId;
    private EarningsEntryType type;
    private String paymentId;
    private String payoutId;
    private String billId;
    private String diningSessionId;
    private String providerPaymentId;
    private String currency;
    private long grossMinor;
    private long feeMinor;
    private long commissionMinor;
    private long netMinor;
    private FeeSource feeSource;
    private Integer commissionBps;
    private String reason;
    private Instant occurredAt;
    private Instant createdAt;

    protected EarningsEntry() {}

    public static String keyOf(EarningsEntryType type, String hotelId, String reference) {
        return type.name() + ":" + hotelId + ":" + reference;
    }

    public static EarningsEntry onlinePayment(String hotelId, String paymentId, String billId, String diningSessionId,
                                              String providerPaymentId, String currency, long grossMinor, long feeMinor,
                                              long commissionMinor, FeeSource feeSource, int commissionBps,
                                              Instant occurredAt, Instant now) {
        EarningsEntry e = new EarningsEntry();
        e.uniqueKey = keyOf(EarningsEntryType.ONLINE_PAYMENT, hotelId, paymentId);
        e.hotelId = hotelId;
        e.type = EarningsEntryType.ONLINE_PAYMENT;
        e.paymentId = paymentId;
        e.billId = billId;
        e.diningSessionId = diningSessionId;
        e.providerPaymentId = providerPaymentId;
        e.currency = currency;
        e.grossMinor = grossMinor;
        e.feeMinor = feeMinor;
        e.commissionMinor = commissionMinor;
        e.netMinor = grossMinor - feeMinor - commissionMinor;
        e.feeSource = feeSource;
        e.commissionBps = commissionBps;
        e.occurredAt = occurredAt;
        e.createdAt = now;
        return e;
    }

    /** Undoes an online payment in full: every amount is the negative of the original. */
    public static EarningsEntry reversalOf(EarningsEntry original, String reason, Instant occurredAt, Instant now) {
        EarningsEntry e = new EarningsEntry();
        e.uniqueKey = keyOf(EarningsEntryType.REFUND, original.hotelId, original.paymentId);
        e.hotelId = original.hotelId;
        e.type = EarningsEntryType.REFUND;
        e.paymentId = original.paymentId;
        e.billId = original.billId;
        e.diningSessionId = original.diningSessionId;
        e.providerPaymentId = original.providerPaymentId;
        e.currency = original.currency;
        e.grossMinor = -original.grossMinor;
        e.feeMinor = -original.feeMinor;
        e.commissionMinor = -original.commissionMinor;
        e.netMinor = -original.netMinor;
        e.feeSource = original.feeSource;
        e.commissionBps = original.commissionBps;
        e.reason = reason;
        e.occurredAt = occurredAt;
        e.createdAt = now;
        return e;
    }

    /** Money paid out to the hotel. Used by step 14.6. */
    public static EarningsEntry payout(String hotelId, String payoutId, long amountMinor, String currency, String reason,
                                       Instant occurredAt, Instant now) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A payout must be more than zero");
        }
        if (payoutId == null || payoutId.isBlank()) {
            throw new IllegalArgumentException("payoutId is required");
        }
        EarningsEntry e = new EarningsEntry();
        e.uniqueKey = keyOf(EarningsEntryType.PAYOUT, hotelId, payoutId);
        e.hotelId = hotelId;
        e.type = EarningsEntryType.PAYOUT;
        e.payoutId = payoutId;
        e.currency = currency;
        e.netMinor = -amountMinor;
        e.reason = reason;
        e.occurredAt = occurredAt;
        e.createdAt = now;
        return e;
    }

    public String getId() { return id; }
    public String getUniqueKey() { return uniqueKey; }
    public String getHotelId() { return hotelId; }
    public EarningsEntryType getType() { return type; }
    public String getPaymentId() { return paymentId; }
    public String getPayoutId() { return payoutId; }
    public String getBillId() { return billId; }
    public String getDiningSessionId() { return diningSessionId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public String getCurrency() { return currency; }
    public long getGrossMinor() { return grossMinor; }
    public long getFeeMinor() { return feeMinor; }
    public long getCommissionMinor() { return commissionMinor; }
    public long getNetMinor() { return netMinor; }
    public FeeSource getFeeSource() { return feeSource; }
    public Integer getCommissionBps() { return commissionBps; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "EarningsEntry{id=" + id + ", key=" + uniqueKey + ", type=" + type + ", gross=" + grossMinor
                + ", fee=" + feeMinor + ", commission=" + commissionMinor + ", net=" + netMinor + "}";
    }
}