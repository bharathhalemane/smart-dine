package com.example.qrorder.billing.dto;

import java.time.Instant;

public record WaitingOrderResponse(String orderId, String sessionId, String tableNumber, String customerLabel,
                                   long subtotalMinor, Instant placedAt, Instant expiresAt, boolean partlyPaid) {
}