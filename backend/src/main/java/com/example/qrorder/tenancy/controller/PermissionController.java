package com.example.qrorder.tenancy.controller;

import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.MyPermissionsResponse;
import com.example.qrorder.tenancy.dto.PermissionSettingsResponse;
import com.example.qrorder.tenancy.dto.UpdatePermissionSettingsRequest;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.PermissionSettingsService;
import jakarta.validation.Valid;
import java.util.EnumSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}")
public class PermissionController {

    private final PermissionSettingsService settings;

    public PermissionController(PermissionSettingsService settings) {
        this.settings = settings;
    }

    /** Any member: lets the frontend show only the buttons this person may use. The server still enforces. */
    @GetMapping("/me/permissions")
    public MyPermissionsResponse mine(@CurrentTenant TenantContext tenant) {
        EnumSet<Permission> permissions = tenant.permissions().isEmpty()
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(tenant.permissions());
        return new MyPermissionsResponse(tenant.roles(), permissions);
    }

    @GetMapping("/staff-permissions")
    public PermissionSettingsResponse getSettings(@CurrentTenant TenantContext tenant) {
        return settings.get(tenant);
    }

    @PatchMapping("/staff-permissions")
    public PermissionSettingsResponse updateSettings(@CurrentTenant TenantContext tenant,
                                                     @Valid @RequestBody UpdatePermissionSettingsRequest request) {
        return settings.update(tenant, request);
    }
}