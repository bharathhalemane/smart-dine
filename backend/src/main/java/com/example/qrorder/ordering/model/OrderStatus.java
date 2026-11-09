package com.example.qrorder.ordering.model;

import java.util.EnumSet;
import java.util.Set;
/** Phase 8 adds the transition rules; for now orders are only ever created PENDING. */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PENDING,
    ACCEPTED,
    PREPARING,
    READY,
    DELIVERED,
    CANCELLED;

    public Set<OrderStatus> next() {
        return switch (this) {
            case AWAITING_PAYMENT -> EnumSet.of(PENDING, CANCELLED);
            case PENDING -> EnumSet.of(ACCEPTED,CANCELLED);
            case ACCEPTED -> EnumSet.of(PREPARING, CANCELLED);
            case PREPARING -> EnumSet.of(READY, CANCELLED);
            case READY -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus other){
        return next().contains(other);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }
}