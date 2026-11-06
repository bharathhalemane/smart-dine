package com.example.qrorder.tables.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Only the SHA-256 hash of the token is stored. The partial unique index lets the database itself
 * guarantee "at most one ACTIVE credential per table"; revoked ones pile up as an audit trail.
 */
@Document("tableQrCredentials")
@CompoundIndex(name = "one_active_per_table", def = "{'tableId': 1}", unique = true,
        partialFilter = "{'status': 'ACTIVE'}")
public class QrCredential {

    @Id
    private String id;

    private String hotelId;
    private String tableId;

    @Indexed(unique = true)
    private String tokenHash;

    private QrStatus status = QrStatus.ACTIVE;
    private String createdBy;
    private Instant createdAt;
    private String revokedBy;
    private Instant revokedAt;

    protected QrCredential() {}

    public QrCredential(String hotelId, String tableId, String tokenHash, String createdBy, Instant createdAt) {
        this.hotelId = hotelId;
        this.tableId = tableId;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getTableId() { return tableId; }
    public String getTokenHash() { return tokenHash; }
    public QrStatus getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRevokedBy() { return revokedBy; }
    public Instant getRevokedAt() { return revokedAt; }

    public void revoke(String byUserId, Instant at) {
        this.status = QrStatus.REVOKED;
        this.revokedBy = byUserId;
        this.revokedAt = at;
    }
}