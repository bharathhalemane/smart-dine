package com.example.qrorder.billing.model;

import java.util.List;

/** One billed line. A copy of what the order recorded, so later menu edits never change a bill. */
public record BillLine(String orderId, String customerLabel, String itemName, String variantName,
                       List<String> options, int quantity, long unitPriceMinor, long lineTotalMinor, String note) {
}