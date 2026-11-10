package com.example.qrorder.dining.event;

/** A table session moved to a new status (bill requested, reopened, closed). */
public record SessionChanged(String hotelId, String diningSessionId, String tableNumber, String status) {
}