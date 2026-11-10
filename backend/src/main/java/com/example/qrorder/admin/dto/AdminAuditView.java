package com.example.qrorder.admin.dto;

import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import java.time.Instant;

public record AdminAuditView(String id, Instant at, AdminAuditAction action, String actorUserId, String actorEmail,
                             String actorName, String targetType, String targetId, String targetLabel,
                             String fromStatus, String toStatus, String reason, String traceId) {

    public static AdminAuditView of(AdminAuditEntry e) {
        return new AdminAuditView(e.getId(), e.getAt(), e.getAction(), e.getActorUserId(), e.getActorEmail(),
                e.getActorName(), e.getTargetType(), e.getTargetId(), e.getTargetLabel(), e.getFromStatus(),
                e.getToStatus(), e.getReason(), e.getTraceId());
    }
}