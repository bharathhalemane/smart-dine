package com.example.qrorder.tables.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** The one waiter responsible for a table. The unique index on tableId is what makes "claim" atomic. */
@Document("tableAssignments")
@CompoundIndex(name = "hotel_waiter", def = "{'hotelId': 1, 'waiterUserId': 1}")
public class TableAssignment {

    @Id
    private String id;

    private String hotelId;

    @Indexed(unique = true)
    private String tableId;

    private String waiterUserId;
    private String assignedByUserId;
    private Instant assignedAt;

    protected TableAssignment() {}

    public TableAssignment(String hotelId, String tableId, String waiterUserId, String assignedByUserId,
                           Instant assignedAt) {
        this.hotelId = hotelId;
        this.tableId = tableId;
        this.waiterUserId = waiterUserId;
        this.assignedByUserId = assignedByUserId;
        this.assignedAt = assignedAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getTableId() { return tableId; }
    public String getWaiterUserId() { return waiterUserId; }
    public String getAssignedByUserId() { return assignedByUserId; }
    public Instant getAssignedAt() { return assignedAt; }
}