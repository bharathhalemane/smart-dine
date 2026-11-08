package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.PaymentSettingsResponse;
import com.example.qrorder.billing.dto.UpdatePaymentSettingsRequest;
import com.example.qrorder.billing.service.PaymentSettingsService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/payments/settings")
public class PaymentSettingsController {

    private final PaymentSettingsService service;

    public PaymentSettingsController(PaymentSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public PaymentSettingsResponse get(@CurrentTenant TenantContext tenant) {
        return service.get(tenant);
    }

    @PutMapping
    public PaymentSettingsResponse update(@CurrentTenant TenantContext tenant,
                                          @Valid @RequestBody UpdatePaymentSettingsRequest request) {
        return service.update(tenant, request);
    }
}