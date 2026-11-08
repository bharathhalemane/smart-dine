package com.example.qrorder.tables.controller;

import com.example.qrorder.tables.dto.WaiterEventResponse;
import com.example.qrorder.tables.service.WaiterAuditService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/waiter-assignments")
public class WaiterAuditController {

    private final WaiterAuditService service;

    public WaiterAuditController(WaiterAuditService service) {
        this.service = service;
    }

    @GetMapping("/history")
    public List<WaiterEventResponse> history(@CurrentTenant TenantContext tenant,
                                             @RequestParam(name = "tableId", required = false) String tableId,
                                             @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return service.history(tenant, tableId, limit);
    }
}