package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.SettlementSettingsResponse;
import com.example.qrorder.billing.dto.UpdateSettlementSettingsRequest;
import com.example.qrorder.billing.service.SettlementSettingsService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/settlement/settings")
public class SettlementSettingsController {

    private final SettlementSettingsService service;

    public SettlementSettingsController(SettlementSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettlementSettingsResponse get(@CurrentTenant TenantContext tenant) {
        return service.get(tenant);
    }

    @PutMapping
    public SettlementSettingsResponse update(@CurrentTenant TenantContext tenant,
                                             @Valid @RequestBody UpdateSettlementSettingsRequest request) {
        return service.update(tenant, request.requireSettlementBeforeClear());
    }
}