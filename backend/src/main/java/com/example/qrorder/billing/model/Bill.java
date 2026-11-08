package com.example.qrorder.billing.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** The bill of one dining session. Exactly one per session (the unique index). All amounts are in paise. */
@Document("bills")
@CompoundIndex(name = "hotel_created", def = "{'hotelId': 1, 'createdAt': -1}")
public class Bill {

    @Id
    private String id;

    private String hotelId;

    @Indexed(unique = true)
    private String diningSessionId;

    private String tableId;
    private String tableNumber;
    private String currency;
    private List<BillLine> lines;
    private long subtotalMinor;
    private long totalMinor;
    private int ordersBilled;
    private int ordersInProgress;
    private int revision;
    private Instant createdAt;
    private Instant updatedAt;

    protected Bill() {}

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getDiningSessionId() { return diningSessionId; }
    public String getTableId() { return tableId; }
    public String getTableNumber() { return tableNumber; }
    public String getCurrency() { return currency; }
    public List<BillLine> getLines() { return lines == null ? List.of() : lines; }
    public long getSubtotalMinor() { return subtotalMinor; }
    public long getTotalMinor() { return totalMinor; }
    public int getOrdersBilled() { return ordersBilled; }
    public int getOrdersInProgress() { return ordersInProgress; }
    public int getRevision() { return revision; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** True when a fresh calculation would produce exactly what is stored. */
    public boolean sameContentAs(List<BillLine> otherLines, long otherSubtotal, int otherBilled, int otherInProgress) {
        return subtotalMinor == otherSubtotal && ordersBilled == otherBilled && ordersInProgress == otherInProgress
                && getLines().equals(otherLines);
    }
}