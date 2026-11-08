package com.example.qrorder.tables.dto;

import com.example.qrorder.tables.model.RequestKind;
import com.example.qrorder.tables.model.RequestStatus;
import java.time.Instant;

public record WaiterRequestResponse(String id, RequestKind kind, RequestStatus status, String tableId,
                                    String tableNumber, String requestedByUserId, String requestedByName,
                                    String note, String targetUserId, String targetName, String decidedByUserId,
                                    String decidedByName, String assignedToUserId, String assignedToName,
                                    String decisionNote, Instant createdAt, Instant decidedAt) {
}