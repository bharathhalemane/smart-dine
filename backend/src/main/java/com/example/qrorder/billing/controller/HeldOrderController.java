package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.ExpiredOrders;
import com.example.qrorder.billing.dto.HoldSettingsResponse;
import com.example.qrorder.billing.dto.UpdateHoldSettingsRequest;
import com.example.qrorder.billing.dto.WaitingOrderResponse;
import com.example.qrorder.billing.service.HeldOrderService;
import com.example.qrorder.billing.service.HeldOrderSettingsService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/payments")
public class HeldOrderController {

    private final HeldOrderService held;
    private final HeldOrderSettingsService settings;

    public HeldOrderController(HeldOrderService held, HeldOrderSettingsService settings) {
        this.held = held;
        this.settings = settings;
    }

    @GetMapping("/hold-settings")
    public HoldSettingsResponse getSettings(@CurrentTenant TenantContext tenant) {
        return settings.get(tenant);
    }

    @PutMapping("/hold-settings")
    public HoldSettingsResponse updateSettings(@CurrentTenant TenantContext tenant,
                                               @Valid @RequestBody UpdateHoldSettingsRequest request) {
        return settings.update(tenant, request.holdMinutes());
    }

    @GetMapping("/waiting-orders")
    public List<WaitingOrderResponse> waiting(@CurrentTenant TenantContext tenant) {
        return held.waiting(tenant);
    }

    @PostMapping("/waiting-orders/expire")
    public ExpiredOrders expire(@CurrentTenant TenantContext tenant) {
        return new ExpiredOrders(held.expireNow(tenant));
    }
}