package com.example.qrorder.billing.event;

/** Something on a table's bill changed: a payment, a void or a discount. */
public record BillChanged(String hotelId, String diningSessionId, String reason) {
}