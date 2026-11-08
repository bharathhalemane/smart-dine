package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.DiscountType;
import java.time.Instant;

/** A discount as shown on a bill. For diners id, reason, byUserId and at are null: only the amount matters. */
public record DiscountView(String id, DiscountType type, long value, long amountMinor, String reason,
                           String byUserId, Instant at) {
}