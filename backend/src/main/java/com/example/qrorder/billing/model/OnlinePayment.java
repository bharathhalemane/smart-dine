package com.example.qrorder.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One attempt by a diner to pay online. The id is also the receipt we gave the provider. */
@Document("onlinePayments")
@CompoundIndexes({
        @CompoundIndex(name = "one_per_key", def = "{'customerSessionId': 1, 'idempotencyKey': 1}", unique = true),
        @CompoundIndex(name = "hotel_status", def = "{'hotelId': 1, 'status': 1, 'updatedAt': 1}")
})
public class OnlinePayment {

    @Id
    private String id;

    private String hotelId;
    private String diningSessionId;
    private String tableNumber;
    private String customerSessionId;
    private String payerLabel;
    private long amountMinor;
    private String currency;
    private String provider;

    @Indexed(unique = true)
    private String providerOrderId;

    private String providerPaymentId;
    private OnlinePaymentStatus status;
    private String reviewReason;
    private String resolutionNote;
    private String resolvedByUserId;
    private String idempotencyKey;
    private String requestHash;
    private Instant createdAt;
    private Instant updatedAt;

    protected OnlinePayment() {}

    public OnlinePayment(String id, String hotelId, String diningSessionId, String tableNumber,
                         String customerSessionId, String payerLabel, long amountMinor, String currency,
                         String provider, String providerOrderId, String idempotencyKey, String requestHash,
                         Instant now) {
        this.id = id;
        this.hotelId = hotelId;
        this.diningSessionId = diningSessionId;
        this.tableNumber = tableNumber;
        this.customerSessionId = customerSessionId;
        this.payerLabel = payerLabel;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.provider = provider;
        this.providerOrderId = providerOrderId;
        this.status = OnlinePaymentStatus.CREATED;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getDiningSessionId() { return diningSessionId; }
    public String getTableNumber() { return tableNumber; }
    public String getCustomerSessionId() { return customerSessionId; }
    public String getPayerLabel() { return payerLabel; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getProvider() { return provider; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public OnlinePaymentStatus getStatus() { return status; }
    public String getReviewReason() { return reviewReason; }
    public String getResolutionNote() { return resolutionNote; }
    public String getResolvedByUserId() { return resolvedByUserId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}