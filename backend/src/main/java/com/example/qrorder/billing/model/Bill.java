package com.example.qrorder.billing.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The bill of one dining session. Exactly one per session (the unique index). All amounts are in paise.
 * Lines and totals are derived from the orders and the hotel's settings; discounts are staff input.
 */
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
    private List<BillDiscount> discounts;
    private long subtotalMinor;
    private long discountMinor;
    private long serviceChargeMinor;
    private long taxMinor;
    private long totalMinor;
    private long paidMinor;
    private String taxName;
    private int taxRateBps;
    private boolean taxInclusive;
    private int serviceChargeBps;
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
    public List<BillDiscount> getDiscounts() { return discounts == null ? List.of() : discounts; }
    public long getSubtotalMinor() { return subtotalMinor; }
    public long getDiscountMinor() { return discountMinor; }
    public long getServiceChargeMinor() { return serviceChargeMinor; }
    public long getTaxMinor() { return taxMinor; }
    public long getTotalMinor() { return totalMinor; }
    public long getPaidMinor() { return paidMinor; }
    public String getTaxName() { return taxName; }
    public int getTaxRateBps() { return taxRateBps; }
    public boolean isTaxInclusive() { return taxInclusive; }
    public int getServiceChargeBps() { return serviceChargeBps; }
    public int getOrdersBilled() { return ordersBilled; }
    public int getOrdersInProgress() { return ordersInProgress; }
    public int getRevision() { return revision; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** True when a fresh calculation would produce exactly what is stored. */
    public boolean matches(BillSnapshot s) {
        return subtotalMinor == s.subtotalMinor() && discountMinor == s.discountMinor()
                && serviceChargeMinor == s.serviceChargeMinor() && taxMinor == s.taxMinor()
                && totalMinor == s.totalMinor() && ordersBilled == s.ordersBilled()
                && ordersInProgress == s.ordersInProgress() && taxRateBps == s.taxRateBps()
                && taxInclusive == s.taxInclusive() && serviceChargeBps == s.serviceChargeBps()
                && java.util.Objects.equals(taxName, s.taxName()) && getLines().equals(s.lines());
    }
}