package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.ReconciliationEntry;
import java.time.Instant;

public record RepairView(String id, String billId, String sessionId, String tableNumber, long beforeMinor,
                         long afterMinor, String reason, String byUserId, Instant at) {

    public static RepairView from(ReconciliationEntry e) {
        return new RepairView(e.getId(), e.getBillId(), e.getDiningSessionId(), e.getTableNumber(),
                e.getBeforeMinor(), e.getAfterMinor(), e.getReason(), e.getByUserId(), e.getAt());
    }
}