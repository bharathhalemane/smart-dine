package com.example.qrorder.payout.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One transfer of a hotel's share of one online payment. One per payment, enforced by the unique index. */
@Document("payoutTransfers")
@CompoundIndex(name = "transfers_status_time", def = "{'status': 1, 'createdAt': 1}")
@CompoundIndex(name = "transfers_hotel_time", def = "{'hotelId': 1, 'createdAt': -1}")
public class PayoutTransfer {

    @Id
    private String id;

    @Indexed(unique = true)
    private String paymentId;

    private String hotelId;
    private String providerPaymentId;
    private long amountMinor;
    private String currency;
    private TransferStatus status;
    private String linkedAccountId;
    private String providerTransferId;
    private String note;
    private int attempts;
    private Instant lockedUntil;
    private Instant createdAt;
    private Instant updatedAt;

    protected PayoutTransfer() {}

    public PayoutTransfer(String hotelId, String paymentId, String providerPaymentId, long amountMinor, String currency,
                          TransferStatus status, String note, Instant now) {
        this.hotelId = hotelId;
        this.paymentId = paymentId;
        this.providerPaymentId = providerPaymentId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.note = note;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getPaymentId() { return paymentId; }
    public String getHotelId() { return hotelId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public TransferStatus getStatus() { return status; }
    public String getLinkedAccountId() { return linkedAccountId; }
    public String getProviderTransferId() { return providerTransferId; }
    public String getNote() { return note; }
    public int getAttempts() { return attempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}