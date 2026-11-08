package com.example.qrorder.ordering.controller;

import com.example.qrorder.ordering.dto.WaiterOrderActionResponse;
import com.example.qrorder.ordering.service.WaiterPickupService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/waiter/orders")
public class WaiterOrderController {

    private final WaiterPickupService service;

    public WaiterOrderController(WaiterPickupService service) {
        this.service = service;
    }

    @PostMapping("/{orderId}/pickup")
    public WaiterOrderActionResponse pickup(@CurrentTenant TenantContext tenant,
                                            @PathVariable("orderId") String orderId) {
        return service.pickup(tenant, orderId);
    }

    @PostMapping("/{orderId}/drop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void drop(@CurrentTenant TenantContext tenant, @PathVariable("orderId") String orderId) {
        service.drop(tenant, orderId);
    }

    @PostMapping("/{orderId}/deliver")
    public WaiterOrderActionResponse deliver(@CurrentTenant TenantContext tenant,
                                             @PathVariable("orderId") String orderId) {
        return service.deliver(tenant, orderId);
    }
}