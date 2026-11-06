package com.example.qrorder.dining.dto;

import com.example.qrorder.dining.model.TableState;
import java.time.Instant;

/** One row of the floor view. sessionId and sessionOpenedAt are null when the table is FREE. */
public record FloorTableResponse(String tableId, String number, int capacity, String section,
                                 TableState state, String sessionId, Instant sessionOpenedAt) {
}