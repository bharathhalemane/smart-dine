package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.ReleasedOrders;
import com.example.qrorder.billing.service.OrderReleaseService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/sessions/{sessionId}/release-paid-orders")
public class OrderReleaseController {

    private final OrderReleaseService service;

    public OrderReleaseController(OrderReleaseService service) {
        this.service = service;
    }

    /** Look at this table again and release any held orders that are now paid for. Safe to repeat. */
    @PostMapping
    public ReleasedOrders release(@CurrentTenant TenantContext tenant, @PathVariable("sessionId") String sessionId) {
        return new ReleasedOrders(service.releaseNow(tenant, sessionId));
    }
}