package com.example.qrorder.admin.dto;

import java.time.Instant;

public record PlatformStats(Instant generatedAt, Hotels hotels, Users users, Activity activity) {

    public record Hotels(long total, long active, long suspended, long deactivated,
                         long createdLast7Days, long createdLast30Days) {
    }

    public record Users(long total, long active, long disabled, long platformAdmins) {
    }

    public record Activity(long openTableSessions, long ordersLast24Hours, long ordersLast7Days,
                           long hotelsWithOrdersLast7Days) {
    }
}