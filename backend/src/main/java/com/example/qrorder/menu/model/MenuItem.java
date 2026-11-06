package com.example.qrorder.menu.model;

import java.time.Instant;
import java.util.Locale;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("menuItems")
@CompoundIndex(name = "hotel_category_name_unique",
        def = "{'hotelId': 1, 'categoryId': 1, 'nameKey': 1}", unique = true)
public class MenuItem {

    @Id
    private String id;

    private String hotelId;
    private String categoryId;
    private String name;
    private String nameKey; // lowercase name: what uniqueness within the category is checked on
    private String description;
    private long priceMinor; // integer paise; the currency is the hotel's
    private Diet diet;
    private int sortOrder;
    private boolean active = true;
    private boolean available = true;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected MenuItem() {}

    public MenuItem(String hotelId, String categoryId, String name, String description, long priceMinor,
                    Diet diet, int sortOrder, Instant now) {
        this.hotelId = hotelId;
        this.categoryId = categoryId;
        setName(name);
        this.description = description;
        this.priceMinor = priceMinor;
        this.diet = diet;
        this.sortOrder = sortOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getCategoryId() { return categoryId; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public String getDescription() { return description; }
    public long getPriceMinor() { return priceMinor; }
    public Diet getDiet() { return diet; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public boolean isAvailable() { return available; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
        this.nameKey = name.toLowerCase(Locale.ROOT);
    }

    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public void setDescription(String description) { this.description = description; }
    public void setPriceMinor(long priceMinor) { this.priceMinor = priceMinor; }
    public void setDiet(Diet diet) { this.diet = diet; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public void setActive(boolean active) { this.active = active; }
    public void setAvailable(boolean available) { this.available = available; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}