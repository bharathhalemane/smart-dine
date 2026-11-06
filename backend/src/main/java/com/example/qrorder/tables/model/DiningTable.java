package com.example.qrorder.tables.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("diningTables")
@CompoundIndex(name = "hotel_number_unique", def = "{'hotelId': 1, 'number': 1}", unique = true)
public class DiningTable {

    @Id
    private String id;

    private String hotelId;
    private String number; // normalized: trimmed, single spaces, uppercase
    private int capacity;
    private String section; // optional, e.g. "Terrace"
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected DiningTable() {}

    public DiningTable(String hotelId, String number, int capacity, String section, Instant now) {
        this.hotelId = hotelId;
        this.number = number;
        this.capacity = capacity;
        this.section = section;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getNumber() { return number; }
    public int getCapacity() { return capacity; }
    public String getSection() { return section; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setNumber(String number) { this.number = number; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public void setSection(String section) { this.section = section; }
    public void setActive(boolean active) { this.active = active; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}