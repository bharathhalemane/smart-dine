package com.example.qrorder.tenancy.controller;

import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.ChangeRolesRequest;
import com.example.qrorder.tenancy.dto.StaffMemberResponse;
import com.example.qrorder.tenancy.service.StaffService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/staff")
public class StaffController {

    private final StaffService service;

    public StaffController(StaffService service) {
        this.service = service;
    }

    @GetMapping
    public List<StaffMemberResponse> list(@CurrentTenant TenantContext tenant) {
        return service.list(tenant);
    }

    @PatchMapping("/{userId}")
    public StaffMemberResponse changeRoles(@CurrentTenant TenantContext tenant,
                                           @PathVariable("userId") String userId,
                                           @Valid @RequestBody ChangeRolesRequest request) {
        return service.changeRoles(tenant, userId, request);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@CurrentTenant TenantContext tenant, @PathVariable("userId") String userId) {
        service.remove(tenant, userId);
    }
}