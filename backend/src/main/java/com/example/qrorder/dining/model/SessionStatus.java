package com.example.qrorder.dining.model;

public enum SessionStatus {
    OPEN,
    BILL_REQUESTED,
    CLOSED;

    public boolean canTransitionTo(SessionStatus next){
        return switch (this) {
            case OPEN -> next == BILL_REQUESTED || next == CLOSED;
            case BILL_REQUESTED -> next == OPEN || next == CLOSED;
            case CLOSED -> false;
        };
    }
}