package com.example.qrorder.admin.controller;

import com.example.qrorder.admin.dto.AdminAuditView;
import com.example.qrorder.admin.dto.HealthInfo;
import com.example.qrorder.admin.dto.PlatformStats;
import com.example.qrorder.admin.service.AdminAuditService;
import com.example.qrorder.admin.service.PlatformHealthService;
import com.example.qrorder.admin.service.PlatformStatsService;
import com.example.qrorder.common.page.PageResponse;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin only: the /api/v1/admin/** rule in SecurityConfig covers every route here. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminPlatformController {

    private final PlatformStatsService stats;
    private final AdminAuditService audit;
    private final PlatformHealthService health;

    public AdminPlatformController(PlatformStatsService stats, AdminAuditService audit, PlatformHealthService health) {
        this.stats = stats;
        this.audit = audit;
        this.health = health;
    }

    @GetMapping("/stats")
    public PlatformStats stats() {
        return stats.stats();
    }

    @GetMapping("/audit")
    public PageResponse<AdminAuditView> audit(
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return audit.search(targetId, actorUserId, action, from, to, page, size);
    }

    @GetMapping("/health")
    public HealthInfo health() {
        return health.check();
    }
}