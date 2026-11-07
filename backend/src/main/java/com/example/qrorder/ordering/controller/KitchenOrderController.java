package com.example.qrorder.ordering.controller;

import com.example.qrorder.ordering.dto.KitchenOrderResponse;
import com.example.qrorder.ordering.service.KitchenOrderService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/kitchen/orders")
public class KitchenOrderController{

    private final KitchenOrderService service;

    public KitchenOrderController(KitchenOrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<KitchenOrderResponse> queue(@CurrentTenant TenantContext tenant,
                                            @RequestParam(name = "status", required = false) List<String> status){
        return service.queue(tenant, status);
    }

    @PostMapping("/{orderId}/accept")
    public KitchenOrderResponse accept(@CurrentTenant TenantContext tenant,
                                       @PathVariable("orderId") String orderId){
        return service.accept(tenant, orderId);
    }

    @PostMapping("/{orderId}/start")
    public KitchenOrderResponse start(@CurrentTenant TenantContext tenant,
                                      @PathVariable("orderId") String orderId){
        return service.start(tenant, orderId);
    }

    @PostMapping("/{orderId}/ready")
    public KitchenOrderResponse ready(@CurrentTenant TenantContext tenant,
                                      @PathVariable("orderId") String orderId){
        return service.ready(tenant, orderId);
    }

    @PostMapping("/{orderId}/deliver")
    public KitchenOrderResponse deliver(@CurrentTenant TenantContext tenant,
                                      @PathVariable("orderId") String orderId){
        return service.deliver(tenant, orderId);
    }
}