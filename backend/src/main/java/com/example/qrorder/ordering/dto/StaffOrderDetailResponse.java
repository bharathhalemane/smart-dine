package com.example.qrorder.ordering.dto;

import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import java.time.Instant;
import java.util.List;

/** Everything staff may see about one order, including who did what and when. */
public record StaffOrderDetailResponse(String id, OrderStatus status, String tableNumber, String customerLabel,
                                       String currency, List<OrderLine> lines, long subtotalMinor,
                                       Instant createdAt, Instant cancelledAt, String cancelledBy,
                                       String cancelReason, List<Entry> history, List<LineReport> lineReports) {

    /** One status change. actorName is the staff member's name, or the guest label for a customer action. */
    public record Entry(OrderStatus from, OrderStatus to, String actorType, String actorId, String actorName,
                        String reason, Instant at) {}

    public record LineReport(int lineNo, String itemName, String reason, String reportedBy, String reportedByName,
                             Instant at) {}
}