package com.example.qrorder.tables.dto;

import java.time.Instant;

/** One table and who is responsible for it. waiterUserId, waiterName and assignedAt are null when it is free. */
public record WaiterAssignmentResponse(String tableId, String tableNumber, String section, String waiterUserId,
                                       String waiterName, Instant assignedAt) {
}