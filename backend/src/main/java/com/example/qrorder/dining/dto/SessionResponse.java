package com.example.qrorder.dining.dto;

import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
import java.time.Instant;

public record SessionResponse(String id, String tableId, String tableNumber, SessionStatus status, Instant openedAt) {

    public static SessionResponse from(DiningSession s) {
        return new SessionResponse(s.getId(), s.getTableId(), s.getTableNumber(), s.getStatus(), s.getOpenedAt());
    }
}