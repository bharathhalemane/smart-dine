package com.example.qrorder.billing.model;

import java.util.List;

/** Everything derived about a bill at one moment. Compared with the stored bill to avoid pointless writes. */
public record BillSnapshot(List<BillLine> lines, long subtotalMinor, long discountMinor, long serviceChargeMinor,
                           long taxMinor, long totalMinor, int ordersBilled, int ordersInProgress, String taxName,
                           int taxRateBps, boolean taxInclusive, int serviceChargeBps) {
}