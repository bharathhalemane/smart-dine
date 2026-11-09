package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.ProviderConfigRequest;
import com.example.qrorder.billing.dto.ProviderConfigResponse;
import com.example.qrorder.billing.service.ProviderConfigService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/payments/provider")
public class ProviderConfigController {

    private final ProviderConfigService service;

    public ProviderConfigController(ProviderConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ProviderConfigResponse get(@CurrentTenant TenantContext tenant) {
        return service.get(tenant);
    }

    @PutMapping
    public ProviderConfigResponse update(@CurrentTenant TenantContext tenant,
                                         @Valid @RequestBody ProviderConfigRequest request) {
        return service.update(tenant, request);
    }
}