package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.BillResponse;
import com.example.qrorder.billing.service.BillService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/sessions/{sessionId}/bill")
public class BillController {

    private final BillService service;

    public BillController(BillService service) {
        this.service = service;
    }

    @GetMapping
    public BillResponse get(@CurrentTenant TenantContext tenant, @PathVariable("sessionId") String sessionId) {
        return service.forStaff(tenant, sessionId);
    }
}