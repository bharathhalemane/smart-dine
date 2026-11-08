package com.example.qrorder.billing.model;

import java.time.Instant;

/** A discount staff put on a bill, with who and why. Kept as given; the amount is worked out from the subtotal. */
public record BillDiscount(String id, DiscountType type, long value, String reason, String byUserId, Instant at) {
}