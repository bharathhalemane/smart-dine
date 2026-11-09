package com.example.qrorder.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("settlement_settings")
public class SettlementSettings {

    @Id
    private String id;

    @Indexed(unique = true)
    private String hotelId;

    private boolean requireSettlementBeforeClear;
    private String updatedBy;
    private Instant updatedAt;

    protected SettlementSettings() {}

    public SettlementSettings(String hotelId, boolean requireSettlementBeforeClear, String updatedBy,
                              Instant updatedAt) {
        this.hotelId = hotelId;
        this.requireSettlementBeforeClear = requireSettlementBeforeClear;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public boolean isRequireSettlementBeforeClear() { return requireSettlementBeforeClear; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}