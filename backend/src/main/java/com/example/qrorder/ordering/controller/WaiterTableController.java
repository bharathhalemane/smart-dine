package com.example.qrorder.ordering.controller;

import com.example.qrorder.dining.dto.SessionDetailResponse;
import com.example.qrorder.ordering.dto.ClearableTableResponse;
import com.example.qrorder.ordering.service.WaiterClearService;
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
@RequestMapping("/api/v1/hotels/{hotelId}/waiter/tables")
public class WaiterTableController {

    private final WaiterClearService service;

    public WaiterTableController(WaiterClearService service) {
        this.service = service;
    }

    @GetMapping("/to-clear")
    public List<ClearableTableResponse> toClear(@CurrentTenant TenantContext tenant,
                                                @RequestParam(name = "scope", defaultValue = "mine") String scope) {
        return service.list(tenant, scope);
    }

    @PostMapping("/{tableId}/clear")
    public SessionDetailResponse clear(@CurrentTenant TenantContext tenant,
                                       @PathVariable("tableId") String tableId,
                                       @RequestParam(name = "force", defaultValue = "false") boolean force) {
        return service.clear(tenant, tableId, force);
    }
}