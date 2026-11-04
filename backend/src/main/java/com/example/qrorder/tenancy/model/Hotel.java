package com.example.qrorder.tenancy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("hotels")
public class Hotel {

    @Id
    private String id;

    @Indexed(unique = true)
    private String slug; // stable public handle; never changes when the hotel is renamed

    private String name;
    private String ownerUserId;
    private HotelStatus status = HotelStatus.ACTIVE;
    private String statusReason;
    private String address;
    private String timezone;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected Hotel() {}

    public Hotel(String slug, String name, String ownerUserId, String address, String timezone,
                 String currency, Instant now) {
        this.slug = slug;
        this.name = name;
        this.ownerUserId = ownerUserId;
        this.address = address;
        this.timezone = timezone;
        this.currency = currency;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getOwnerUserId() { return ownerUserId; }
    public HotelStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public String getAddress() { return address; }
    public String getTimezone() { return timezone; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setAddress(String address) { this.address = address; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public void setStatus(HotelStatus status) { this.status = status; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}