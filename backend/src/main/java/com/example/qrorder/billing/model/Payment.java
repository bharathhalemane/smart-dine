package com.example.qrorder.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One entry of a table's payment ledger. Entries are never deleted: a mistake is voided, with who and why.
 * The unique index makes a retried request (same Idempotency-Key) find its original entry.
 */
@Document("payments")
@CompoundIndexes({
        @CompoundIndex(name = "one_per_key", def = "{'diningSessionId': 1, 'idempotencyKey': 1}", unique = true),
        @CompoundIndex(name = "session_received", def = "{'diningSessionId': 1, 'receivedAt': 1}"),
        @CompoundIndex(name = "hotel_received", def = "{'hotelId': 1, 'receivedAt': -1}")
})
public class Payment {

    @Id
    private String id;

    private String hotelId;
    private String diningSessionId;
    private String billId;
    private PaymentMethod method;
    private long amountMinor;
    private PaymentStatus status;
    private String reference;
    private String note;
    private String payerLabel;
    private String receivedByUserId;
    private Instant receivedAt;
    private String idempotencyKey;
    private String requestHash;
    private String voidedByUserId;
    private Instant voidedAt;
    private String voidReason;

    protected Payment() {}

    public Payment(String hotelId, String diningSessionId, String billId, PaymentMethod method, long amountMinor,
                   String reference, String note, String payerLabel, String receivedByUserId, Instant receivedAt,
                   String idempotencyKey, String requestHash) {
        this.hotelId = hotelId;
        this.diningSessionId = diningSessionId;
        this.billId = billId;
        this.method = method;
        this.amountMinor = amountMinor;
        this.status = PaymentStatus.RECORDED;
        this.reference = reference;
        this.note = note;
        this.payerLabel = payerLabel;
        this.receivedByUserId = receivedByUserId;
        this.receivedAt = receivedAt;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getDiningSessionId() { return diningSessionId; }
    public String getBillId() { return billId; }
    public PaymentMethod getMethod() { return method; }
    public long getAmountMinor() { return amountMinor; }
    public PaymentStatus getStatus() { return status; }
    public String getReference() { return reference; }
    public String getNote() { return note; }
    public String getPayerLabel() { return payerLabel; }
    public String getReceivedByUserId() { return receivedByUserId; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getVoidedByUserId() { return voidedByUserId; }
    public Instant getVoidedAt() { return voidedAt; }
    public String getVoidReason() { return voidReason; }
}