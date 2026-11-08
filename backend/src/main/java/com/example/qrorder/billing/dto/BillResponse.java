package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.BillLine;
import com.example.qrorder.dining.model.SessionStatus;
import java.time.Instant;
import java.util.List;

/**
 * complete is false while orders are still in progress, so the bill may still grow.
 * revision goes up each time the content changes.
 */
public record BillResponse(String id, String sessionId, SessionStatus sessionStatus, String tableNumber,
                           String currency, List<BillLine> lines, List<GuestTotal> guests, long subtotalMinor,
                           long totalMinor, int ordersBilled, int ordersInProgress, boolean complete, int revision,
                           Instant updatedAt) {
}