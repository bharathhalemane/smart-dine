package com.example.qrorder.tenancy.controller;

import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.InvitationResponse;
import com.example.qrorder.tenancy.dto.InviteStaffRequest;
import com.example.qrorder.tenancy.service.StaffInvitationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/invitations")
public class StaffInvitationController {

    private final StaffInvitationService service;

    public StaffInvitationController(StaffInvitationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@CurrentTenant TenantContext tenant,
                                     @Valid @RequestBody InviteStaffRequest request) {
        return service.invite(tenant, request);
    }

    @GetMapping
    public List<InvitationResponse> pending(@CurrentTenant TenantContext tenant) {
        return service.listPending(tenant);
    }
}