package com.example.qrorder.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A hotel's tax and service-charge setup. A hotel with no document of its own charges neither. */
@Document("billingSettings")
public class BillingSettings {

    @Id
    private String id;

    @Indexed(unique = true)
    private String hotelId;

    private String taxName;
    private int taxRateBps;
    private boolean taxInclusive;
    private int serviceChargeBps;
    private String updatedByUserId;
    private Instant updatedAt;

    protected BillingSettings() {}

    public BillingSettings(String hotelId, String taxName, int taxRateBps, boolean taxInclusive,
                           int serviceChargeBps, String updatedByUserId, Instant updatedAt) {
        this.hotelId = hotelId;
        this.taxName = taxName;
        this.taxRateBps = taxRateBps;
        this.taxInclusive = taxInclusive;
        this.serviceChargeBps = serviceChargeBps;
        this.updatedByUserId = updatedByUserId;
        this.updatedAt = updatedAt;
    }

    public static BillingSettings defaults(String hotelId) {
        return new BillingSettings(hotelId, "GST", 0, false, 0, null, null);
    }

    public String getHotelId() { return hotelId; }
    public String getTaxName() { return taxName; }
    public int getTaxRateBps() { return taxRateBps; }
    public boolean isTaxInclusive() { return taxInclusive; }
    public int getServiceChargeBps() { return serviceChargeBps; }
    public String getUpdatedByUserId() { return updatedByUserId; }
    public Instant getUpdatedAt() { return updatedAt; }
}