package com.example.qrorder.dining.model;

/** A table's state is derived from its active session, never stored, so it cannot drift out of sync. */
public enum TableState {
    FREE,
    OCCUPIED,
    BILL_REQUESTED;

    public static TableState of(SessionStatus activeSessionStatus) {
        if (activeSessionStatus == null) {
            return FREE;
        }
        return switch (activeSessionStatus) {
            case OPEN -> OCCUPIED;
            case BILL_REQUESTED -> BILL_REQUESTED;
            case CLOSED -> FREE;
        };
    }
}