package com.example.qrorder.tables.controller;

import com.example.qrorder.tables.dto.ApproveRequest;
import com.example.qrorder.tables.dto.DeclineRequest;
import com.example.qrorder.tables.dto.RaiseRequest;
import com.example.qrorder.tables.dto.WaiterRequestResponse;
import com.example.qrorder.tables.service.WaiterRequestService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}")
public class WaiterRequestController {

    private final WaiterRequestService service;

    public WaiterRequestController(WaiterRequestService service) {
        this.service = service;
    }

    @PostMapping("/tables/{tableId}/waiter/requests")
    public ResponseEntity<WaiterRequestResponse> raise(@CurrentTenant TenantContext tenant,
                                                       @PathVariable("tableId") String tableId,
                                                       @Valid @RequestBody RaiseRequest body) {
        WaiterRequestService.Raised raised = service.raise(tenant, tableId, body);
        return ResponseEntity.status(raised.created() ? HttpStatus.CREATED : HttpStatus.OK).body(raised.request());
    }

    @GetMapping("/waiter-requests")
    public List<WaiterRequestResponse> open(@CurrentTenant TenantContext tenant) {
        return service.listOpen(tenant);
    }

    @GetMapping("/waiter-requests/mine")
    public List<WaiterRequestResponse> mine(@CurrentTenant TenantContext tenant) {
        return service.mine(tenant);
    }

    @PostMapping("/waiter-requests/{requestId}/respond")
    public WaiterRequestResponse respond(@CurrentTenant TenantContext tenant,
                                         @PathVariable("requestId") String requestId) {
        return service.respond(tenant, requestId);
    }

    @PostMapping("/waiter-requests/{requestId}/cancel")
    public WaiterRequestResponse cancel(@CurrentTenant TenantContext tenant,
                                        @PathVariable("requestId") String requestId) {
        return service.cancel(tenant, requestId);
    }

    @PostMapping("/waiter-requests/{requestId}/approve")
    public WaiterRequestResponse approve(@CurrentTenant TenantContext tenant,
                                         @PathVariable("requestId") String requestId,
                                         @Valid @RequestBody(required = false) ApproveRequest body) {
        return service.approve(tenant, requestId, body == null ? null : body.toUserId());
    }

    @PostMapping("/waiter-requests/{requestId}/decline")
    public WaiterRequestResponse decline(@CurrentTenant TenantContext tenant,
                                         @PathVariable("requestId") String requestId,
                                         @Valid @RequestBody(required = false) DeclineRequest body) {
        return service.decline(tenant, requestId, body == null ? null : body.reason());
    }
}