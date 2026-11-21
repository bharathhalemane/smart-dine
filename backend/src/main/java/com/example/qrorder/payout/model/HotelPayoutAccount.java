package com.example.qrorder.payout.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** The Razorpay linked account (acc_...) the platform sends a hotel's share to. */
@Document("payoutAccounts")
public class HotelPayoutAccount {

    @Id
    private String id;

    @Indexed(unique = true)
    private String hotelId;

    private String linkedAccountId;
    private String updatedBy;
    private Instant updatedAt;

    protected HotelPayoutAccount() {}

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getLinkedAccountId() { return linkedAccountId; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}