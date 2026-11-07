package com.example.qrorder.ordering.model;

/** Phase 8 adds the transition rules; for now orders are only ever created PENDING. */
public enum OrderStatus {
    PENDING,
    ACCEPTED,
    PREPARING,
    READY,
    DELIVERED,
    CANCELLED
}