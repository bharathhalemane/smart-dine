package com.example.qrorder.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** How long an order may wait for payment (pay-before-food hotels) before it is cancelled. */
@Document("held_order_settings")
public class HeldOrderSettings {

    public static final int DEFAULT_MINUTES = 15;
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 180;

    @Id
    private String id;

    @Indexed(unique = true)
    private String hotelId;

    private int holdMinutes;
    private String updatedBy;
    private Instant updatedAt;

    protected HeldOrderSettings() {}

    public HeldOrderSettings(String hotelId, int holdMinutes, String updatedBy, Instant updatedAt) {
        this.hotelId = hotelId;
        this.holdMinutes = holdMinutes;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public int getHoldMinutes() { return holdMinutes; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}