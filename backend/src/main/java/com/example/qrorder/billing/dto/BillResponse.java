package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.BillLine;
import com.example.qrorder.dining.model.SessionStatus;
import java.time.Instant;
import java.util.List;

/**
 * totalMinor = subtotal - discounts + service charge + tax (tax is already inside the prices when taxInclusive).
 * complete is false while orders are still in progress, so the bill may still grow.
 * revision goes up each time the content changes.
 */
public record BillResponse(String id, String sessionId, SessionStatus sessionStatus, String tableNumber,
                           String currency, List<BillLine> lines, List<GuestTotal> guests, long subtotalMinor,
                           long discountMinor, long serviceChargeMinor, long taxMinor, long totalMinor,
                           long paidMinor, long balanceMinor, long overpaidMinor, String taxName, int taxRateBps,
                           boolean taxInclusive, int serviceChargeBps, List<DiscountView> discounts,
                           int ordersBilled, int ordersInProgress, boolean complete, int revision,
                           Instant updatedAt) {
}