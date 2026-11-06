package com.example.qrorder.dining.model;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One group's visit at a table. The partial unique index makes the database itself guarantee
 * "at most one active session per table", whatever the application does under concurrency.
 * `active` mirrors "status is not CLOSED"; it exists because partial indexes cannot express "not closed".
 */
@Document("diningSessions")
@CompoundIndexes({
        @CompoundIndex(name = "one_active_per_table", def = "{'tableId': 1}", unique = true,
                partialFilter = "{'active': true}"),
        @CompoundIndex(name = "hotel_active", def = "{'hotelId': 1, 'active': 1}")
})
public class DiningSession {

    @Id
    private String id;

    private String hotelId;
    private String tableId;
    private String tableNumber; // snapshot, so history reads correctly even if the table is renamed
    private SessionStatus status = SessionStatus.OPEN;
    private boolean active = true;
    private Instant openedAt;
    private Instant closedAt;
    private List<StatusChange> statusHistory = new ArrayList<>();

    @Version
    private Long version;

    protected DiningSession() {}

    public DiningSession(String hotelId, String tableId, String tableNumber, Instant openedAt) {
        this.hotelId = hotelId;
        this.tableId = tableId;
        this.tableNumber = tableNumber;
        this.openedAt = openedAt;
    }

    public record StatusChange(SessionStatus from, SessionStatus to, String byUserId, Instant at){}

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getTableId() { return tableId; }
    public String getTableNumber() { return tableNumber; }
    public SessionStatus getStatus() { return status; }
    public boolean isActive() { return active; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public List<StatusChange> getStatusHistory() {
        return statusHistory == null ? List.of() : List.copyOf(statusHistory);
    }

    public void close(Instant at) {
        this.status = SessionStatus.CLOSED;
        this.active = false;
        this.closedAt = at;
    }
}