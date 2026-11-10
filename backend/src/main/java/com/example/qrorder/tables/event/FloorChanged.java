package com.example.qrorder.tables.event;

/** Something happened on the floor that waiters should look at again (the kind is the audit event type). */
public record FloorChanged(String hotelId, String kind, String tableId, String tableNumber, String orderId) {
}