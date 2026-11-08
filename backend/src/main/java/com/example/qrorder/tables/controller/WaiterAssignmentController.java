package com.example.qrorder.tables.controller;

import com.example.qrorder.tables.dto.AssignWaiterRequest;
import com.example.qrorder.tables.dto.WaiterAssignmentResponse;
import com.example.qrorder.tables.service.WaiterAssignmentService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}")
public class WaiterAssignmentController {

    private final WaiterAssignmentService service;

    public WaiterAssignmentController(WaiterAssignmentService service) {
        this.service = service;
    }

    @PutMapping("/tables/{tableId}/waiter")
    public WaiterAssignmentResponse assign(@CurrentTenant TenantContext tenant,
                                           @PathVariable("tableId") String tableId,
                                           @Valid @RequestBody AssignWaiterRequest request) {
        return service.assign(tenant, tableId, request.userId());
    }

    @DeleteMapping("/tables/{tableId}/waiter")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId) {
        service.unassign(tenant, tableId);
    }

    @PostMapping("/tables/{tableId}/waiter/claim")
    public WaiterAssignmentResponse claim(@CurrentTenant TenantContext tenant,
                                          @PathVariable("tableId") String tableId) {
        return service.claim(tenant, tableId);
    }

    @PostMapping("/tables/{tableId}/waiter/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId) {
        service.release(tenant, tableId);
    }

    @GetMapping("/waiter-assignments")
    public List<WaiterAssignmentResponse> list(@CurrentTenant TenantContext tenant) {
        return service.list(tenant);
    }

    @GetMapping("/waiter-assignments/mine")
    public List<WaiterAssignmentResponse> mine(@CurrentTenant TenantContext tenant) {
        return service.mine(tenant);
    }
}