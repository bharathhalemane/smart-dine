package com.example.qrorder.ordering.dto;

import com.example.qrorder.dining.model.SessionStatus;
import java.time.Instant;

/** A table with a live session, and whether it can be cleared right now. */
public record ClearableTableResponse(String tableId, String tableNumber, String section, String sessionId,
                                     SessionStatus sessionStatus, Instant openedAt, int ordersInProgress,
                                     boolean readyToClear, String blockedReason, String waiterUserId,
                                     String waiterName, boolean mine, boolean unassigned) {
}