package com.example.qrorder.ordering.dto;

import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import java.time.Instant;
import java.util.List;

/** What the customer gets back. No internal ids beyond the order's own. */
public record OrderResponse(String id, OrderStatus status, String tableNumber, String customerLabel,
                            String currency, List<OrderLine> lines, long subtotalMinor, Instant createdAt,
                            Instant cancelledAt) {

    public static OrderResponse from(Order o) {
        return new OrderResponse(o.getId(), o.getStatus(), o.getTableNumber(), o.getCustomerLabel(),
                o.getCurrency(), o.getLines(), o.getSubtotalMinor(), o.getCreatedAt(), o.getCancelledAt());
    }
}