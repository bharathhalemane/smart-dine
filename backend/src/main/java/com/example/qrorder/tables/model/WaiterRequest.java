package com.example.qrorder.tables.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A waiter asking for help at a table, or asking to hand a table over. Decided exactly once. */
@Document("waiterRequests")
@CompoundIndexes({
        @CompoundIndex(name = "one_open_per_kind",
                def = "{'tableId': 1, 'requestedByUserId': 1, 'kind': 1}",
                unique = true, partialFilter = "{'status': 'OPEN'}"),
        @CompoundIndex(name = "hotel_status_created", def = "{'hotelId': 1, 'status': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "hotel_user_created", def = "{'hotelId': 1, 'requestedByUserId': 1, 'createdAt': -1}")
})
public class WaiterRequest {

    @Id
    private String id;

    private String hotelId;
    private String tableId;
    private String tableNumber;
    private RequestKind kind;
    private RequestStatus status;
    private String requestedByUserId;
    private String note;
    private String targetUserId;      // the colleague a hand-over request names, if any
    private Instant createdAt;
    private String decidedByUserId;
    private Instant decidedAt;
    private String assignedToUserId;  // who actually received the table when approved
    private String decisionNote;

    protected WaiterRequest() {}

    public WaiterRequest(String hotelId, String tableId, String tableNumber, RequestKind kind,
                         String requestedByUserId, String note, String targetUserId, Instant createdAt) {
        this.hotelId = hotelId;
        this.tableId = tableId;
        this.tableNumber = tableNumber;
        this.kind = kind;
        this.status = RequestStatus.OPEN;
        this.requestedByUserId = requestedByUserId;
        this.note = note;
        this.targetUserId = targetUserId;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getTableId() { return tableId; }
    public String getTableNumber() { return tableNumber; }
    public RequestKind getKind() { return kind; }
    public RequestStatus getStatus() { return status; }
    public String getRequestedByUserId() { return requestedByUserId; }
    public String getNote() { return note; }
    public String getTargetUserId() { return targetUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDecidedByUserId() { return decidedByUserId; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getAssignedToUserId() { return assignedToUserId; }
    public String getDecisionNote() { return decisionNote; }
}