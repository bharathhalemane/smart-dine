package com.example.qrorder.ordering.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A confirmed order for one table visit. Created in Step 7.4; the model lands here with the pricing. */
@Document("orders")
@CompoundIndexes({
        @CompoundIndex(name = "hotel_status_created", def = "{'hotelId': 1, 'status': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "session_created", def = "{'diningSessionId': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "one_order_per_key",def="{'customerSessionId':1, 'idempotencyKey': 1}", unique=true,
        partialFilter = "{'idempotencyKey':{$type: 'string'}}")
})
public class Order {

    @Id
    private String id;

    private String hotelId;
    private String diningSessionId;
    private String tableId;
    private String tableNumber;      // snapshot
    private String customerSessionId;
    private String customerLabel;    // snapshot: "Guest 2" or the name they chose
    private OrderStatus status = OrderStatus.PENDING;
    private String currency;
    private List<OrderLine> lines = new ArrayList<>();
    private long subtotalMinor;
    private String idempotencyKey;
    private String requestHash;
    private Instant createdAt;
    private Instant cancelledAt;
    private String cancelledBy;

    @Version
    private Long version;

    protected Order() {}

    public Order(String hotelId, String diningSessionId, String tableId, String tableNumber,
                 String customerSessionId, String customerLabel, String currency, List<OrderLine> lines,
                 long subtotalMinor,String idempotencyKey, String requestHash, Instant createdAt) {
        this.hotelId = hotelId;
        this.diningSessionId = diningSessionId;
        this.tableId = tableId;
        this.tableNumber = tableNumber;
        this.customerSessionId = customerSessionId;
        this.customerLabel = customerLabel;
        this.currency = currency;
        this.lines =new ArrayList<>(lines);
        this.subtotalMinor = subtotalMinor;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getDiningSessionId() { return diningSessionId; }
    public String getTableId() { return tableId; }
    public String getTableNumber() { return tableNumber; }
    public String getCustomerSessionId() { return customerSessionId; }
    public String getCustomerLabel() { return customerLabel; }
    public OrderStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public List<OrderLine> getLines() { return lines == null ? List.of() : List.copyOf(lines); }
    public long getSubtotalMinor() { return subtotalMinor; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancelledBy() { return cancelledBy; }
}