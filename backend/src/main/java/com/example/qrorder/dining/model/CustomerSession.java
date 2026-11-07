package com.example.qrorder.dining.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One diner's device during one table visit. Only the hash of the cookie token is stored.
 * Rows disappear on their own after 7 days (TTL index on createdAt).
 */
@Document("customerSessions")
public class CustomerSession {

    @Id
    private String id;

    @Indexed(unique = true)
    private String tokenHash;

    @Indexed
    private String diningSessionId;

    private String hotelId;
    private String tableId;

    @Indexed(expireAfterSeconds = 604800)
    private Instant createdAt;

    protected CustomerSession() {}

    public CustomerSession(String hotelId, String tableId, String diningSessionId, String tokenHash,
                           Instant createdAt) {
        this.hotelId = hotelId;
        this.tableId = tableId;
        this.diningSessionId = diningSessionId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public String getDiningSessionId() { return diningSessionId; }
    public String getHotelId() { return hotelId; }
    public String getTableId() { return tableId; }
    public Instant getCreatedAt() { return createdAt; }
}