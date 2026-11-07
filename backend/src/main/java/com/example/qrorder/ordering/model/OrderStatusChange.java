package com.example.qrorder.ordering.model;

import java.time.Instant;

public record OrderStatusChange(OrderStatus from, OrderStatus to, String actorType, String actorId,
                                String reason, Instant at) {
}