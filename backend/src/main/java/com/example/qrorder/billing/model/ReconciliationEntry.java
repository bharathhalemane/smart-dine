package com.example.qrorder.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A record that someone changed a bill's paid counter to match the ledger. Never edited or deleted. */
@Document("reconciliationLog")
@CompoundIndex(name = "hotel_at", def = "{'hotelId': 1, 'at': -1}")
public class ReconciliationEntry {

    @Id
    private String id;

    private String hotelId;
    private String billId;
    private String diningSessionId;
    private String tableNumber;
    private long beforeMinor;
    private long afterMinor;
    private String reason;
    private String byUserId;
    private Instant at;

    protected ReconciliationEntry() {}

    public ReconciliationEntry(String hotelId, String billId, String diningSessionId, String tableNumber,
                               long beforeMinor, long afterMinor, String reason, String byUserId, Instant at) {
        this.hotelId = hotelId;
        this.billId = billId;
        this.diningSessionId = diningSessionId;
        this.tableNumber = tableNumber;
        this.beforeMinor = beforeMinor;
        this.afterMinor = afterMinor;
        this.reason = reason;
        this.byUserId = byUserId;
        this.at = at;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getBillId() { return billId; }
    public String getDiningSessionId() { return diningSessionId; }
    public String getTableNumber() { return tableNumber; }
    public long getBeforeMinor() { return beforeMinor; }
    public long getAfterMinor() { return afterMinor; }
    public String getReason() { return reason; }
    public String getByUserId() { return byUserId; }
    public Instant getAt() { return at; }
}