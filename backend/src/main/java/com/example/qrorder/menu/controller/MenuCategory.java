package com.example.qrorder.menu.model;

import java.time.Instant;
import java.util.Locale;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("menuCategories")
@CompoundIndex(name = "hotel_name_unique", def = "{'hotelId': 1, 'nameKey': 1}", unique = true)
public class MenuCategory {

    @Id
    private String id;

    private String hotelId;
    private String name;    // as shown to customers
    private String nameKey; // lowercase name: what uniqueness is checked on
    private String description;
    private int sortOrder;
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected MenuCategory() {}

    public MenuCategory(String hotelId, String name, String description, int sortOrder, Instant now) {
        this.hotelId = hotelId;
        setName(name);
        this.description = description;
        this.sortOrder = sortOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public String getDescription() { return description; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
        this.nameKey = name.toLowerCase(Locale.ROOT);
    }

    public void setDescription(String description) { this.description = description; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public void setActive(boolean active) { this.active = active; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}