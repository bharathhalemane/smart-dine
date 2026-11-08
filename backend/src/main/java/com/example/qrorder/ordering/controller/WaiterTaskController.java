package com.example.qrorder.ordering.controller;

import com.example.qrorder.ordering.dto.WaiterTaskResponse;
import com.example.qrorder.ordering.service.WaiterTaskService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/waiter")
public class WaiterTaskController {

    private final WaiterTaskService service;

    public WaiterTaskController(WaiterTaskService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public List<WaiterTaskResponse> tasks(@CurrentTenant TenantContext tenant,
                                          @RequestParam(name = "scope", defaultValue = "mine") String scope) {
        return service.tasks(tenant, scope);
    }
}