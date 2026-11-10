package com.example.qrorder.admin.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One thing a platform admin did. Written together with the change it describes and never edited or deleted.
 * Who and what are copied in, so the entry still reads correctly if the account or hotel is renamed later.
 */
@Document("admin_audit")
@CompoundIndex(name = "target_time", def = "{'targetId': 1, 'at': -1}")
public class AdminAuditEntry {

    @Id
    private String id;

    @Indexed
    private Instant at;

    private String actorUserId;
    private String actorEmail;
    private String actorName;
    private AdminAuditAction action;
    private String targetType;
    private String targetId;
    private String targetLabel;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String traceId;

    protected AdminAuditEntry() {}

    public AdminAuditEntry(Instant at, String actorUserId, String actorEmail, String actorName,
                           AdminAuditAction action, String targetType, String targetId, String targetLabel,
                           String fromStatus, String toStatus, String reason, String traceId) {
        this.at = at;
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.actorName = actorName;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetLabel = targetLabel;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.traceId = traceId;
    }

    public String getId() { return id; }
    public Instant getAt() { return at; }
    public String getActorUserId() { return actorUserId; }
    public String getActorEmail() { return actorEmail; }
    public String getActorName() { return actorName; }
    public AdminAuditAction getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getTargetLabel() { return targetLabel; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public String getTraceId() { return traceId; }
}