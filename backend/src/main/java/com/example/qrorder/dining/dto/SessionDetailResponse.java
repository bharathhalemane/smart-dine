package com.example.qrorder.dining.dto;

import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.DiningSession.StatusChange;
import com.example.qrorder.dining.model.SessionStatus;
import java.time.Instant;
import java.util.List;

public record SessionDetailResponse(String id, String tableId, String tableNumber, SessionStatus status,
                                    Instant openedAt, Instant closedAt, List<StatusChange> history) {

    public static SessionDetailResponse from(DiningSession s) {
        return new SessionDetailResponse(s.getId(), s.getTableId(), s.getTableNumber(), s.getStatus(),
                s.getOpenedAt(), s.getClosedAt(), s.getStatusHistory());
    }
}