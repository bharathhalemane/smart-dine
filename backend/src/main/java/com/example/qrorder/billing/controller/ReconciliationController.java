package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.ExpireResult;
import com.example.qrorder.billing.dto.ReconciliationReport;
import com.example.qrorder.billing.dto.RepairBillRequest;
import com.example.qrorder.billing.dto.RepairResult;
import com.example.qrorder.billing.dto.RepairView;
import com.example.qrorder.billing.service.ReconciliationService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    public ReconciliationReport report(@CurrentTenant TenantContext tenant,
                                       @RequestParam(name = "days", defaultValue = "7") int days) {
        return service.report(tenant, days);
    }

    @PostMapping("/bills/{billId}/repair")
    public RepairResult repair(@CurrentTenant TenantContext tenant, @PathVariable("billId") String billId,
                               @Valid @RequestBody RepairBillRequest request) {
        return service.repair(tenant, billId, request.reason());
    }

    @GetMapping("/repairs")
    public List<RepairView> repairs(@CurrentTenant TenantContext tenant) {
        return service.repairs(tenant);
    }

    @PostMapping("/online-attempts/expire")
    public ExpireResult expire(@CurrentTenant TenantContext tenant,
                               @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return service.expireStaleAttempts(tenant, hours);
    }
}