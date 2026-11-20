package com.example.qrorder.earnings.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("earningsSettings")
public class EarningsSettings {

    @Id
    private String id;

    @Indexed(unique = true)
    private String hotelId;

    private int commissionBps;
    private String updatedBy;
    private Instant updatedAt;

    protected EarningsSettings() {}

    public EarningsSettings(String hotelId, int commissionBps, String updatedBy, Instant updatedAt){
        this.hotelId = hotelId;
        this.commissionBps = commissionBps;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getHotelId() {
        return hotelId;
    }

    public int getCommissionBps() {
        return commissionBps;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
    
    