package com.example.qrorder.tables.dto;

import com.example.qrorder.tables.model.WaiterEventType;
import java.time.Instant;

public record WaiterEventResponse(String id, WaiterEventType type, String tableId, String tableNumber,
                                  String orderId, String actorUserId, String actorName, String fromUserId,
                                  String fromName, String toUserId, String toName, String note, Instant at) {
}