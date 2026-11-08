package com.example.qrorder.tables.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** One line of the floor audit log. Written once, never changed. */
@Document("waiterEvents")
@CompoundIndexes({
        @CompoundIndex(name = "hotel_at", def = "{'hotelId': 1, 'at': -1}"),
        @CompoundIndex(name = "hotel_table_at", def = "{'hotelId': 1, 'tableId': 1, 'at': -1}")
})
public class WaiterEvent {

    @Id
    private String id;

    private String hotelId;
    private WaiterEventType type;
    private String tableId;
    private String tableNumber;
    private String orderId;
    private String actorUserId;
    private String fromUserId;
    private String toUserId;
    private String note;
    private Instant at;

    protected WaiterEvent() {}

    public WaiterEvent(String hotelId, WaiterEventType type, String tableId, String tableNumber, String orderId,
                       String actorUserId, String fromUserId, String toUserId, String note, Instant at) {
        this.hotelId = hotelId;
        this.type = type;
        this.tableId = tableId;
        this.tableNumber = tableNumber;
        this.orderId = orderId;
        this.actorUserId = actorUserId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.note = note;
        this.at = at;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public WaiterEventType getType() { return type; }
    public String getTableId() { return tableId; }
    public String getTableNumber() { return tableNumber; }
    public String getOrderId() { return orderId; }
    public String getActorUserId() { return actorUserId; }
    public String getFromUserId() { return fromUserId; }
    public String getToUserId() { return toUserId; }
    public String getNote() { return note; }
    public Instant getAt() { return at; }
}