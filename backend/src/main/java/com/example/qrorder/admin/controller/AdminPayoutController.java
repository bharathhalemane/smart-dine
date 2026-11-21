package com.example.qrorder.admin.controller;

import com.example.qrorder.admin.dto.PayoutAccountResponse;
import com.example.qrorder.admin.dto.UpdatePayoutAccountRequest;
import com.example.qrorder.admin.service.PayoutAccountAdminService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin only: /api/v1/admin/** is restricted in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/hotels/{id}/payout-account")
public class AdminPayoutController {

    private final PayoutAccountAdminService service;

    public AdminPayoutController(PayoutAccountAdminService service) {
        this.service = service;
    }

    @GetMapping
    public PayoutAccountResponse get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PutMapping
    public PayoutAccountResponse set(Authentication who, @PathVariable("id") String id,
                                     @Valid @RequestBody UpdatePayoutAccountRequest request) {
        return service.update(who.getName(), id, request.linkedAccountId(), request.reason());
    }
}