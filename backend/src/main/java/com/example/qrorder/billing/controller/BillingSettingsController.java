package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.BillingSettingsResponse;
import com.example.qrorder.billing.dto.UpdateBillingSettingsRequest;
import com.example.qrorder.billing.service.BillingSettingsService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/billing/settings")
public class BillingSettingsController {

    private final BillingSettingsService service;

    public BillingSettingsController(BillingSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public BillingSettingsResponse get(@CurrentTenant TenantContext tenant) {
        return service.get(tenant);
    }

    @PutMapping
    public BillingSettingsResponse update(@CurrentTenant TenantContext tenant,
                                          @Valid @RequestBody UpdateBillingSettingsRequest request) {
        return service.update(tenant, request);
    }
}