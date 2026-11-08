package com.example.qrorder.ordering.controller;

import com.example.qrorder.ordering.dto.CancelOrderRequest;
import com.example.qrorder.ordering.dto.StaffOrderDetailResponse;
import com.example.qrorder.ordering.service.StaffOrderService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/kitchen/orders/{orderId}")
public class StaffOrderController {

    private final StaffOrderService service;

    public StaffOrderController(StaffOrderService service) {
        this.service = service;
    }

    @GetMapping
    public StaffOrderDetailResponse detail(@CurrentTenant TenantContext tenant,
                                           @PathVariable("orderId") String orderId) {
        return service.detail(tenant, orderId);
    }

    @PostMapping("/cancel")
    public StaffOrderDetailResponse cancel(@CurrentTenant TenantContext tenant,
                                           @PathVariable("orderId") String orderId,
                                           @Valid @RequestBody(required = false) CancelOrderRequest body) {
        return service.cancel(tenant, orderId, body == null ? null : body.reason());
    }
}