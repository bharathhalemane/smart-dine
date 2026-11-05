package com.example.qrorder.tenancy.controller;

import com.example.qrorder.tenancy.dto.AcceptNewAccountRequest;
import com.example.qrorder.tenancy.dto.InvitationAcceptedResponse;
import com.example.qrorder.tenancy.dto.InvitationPreviewResponse;
import com.example.qrorder.tenancy.dto.InvitationTokenRequest;
import com.example.qrorder.tenancy.service.InvitationAcceptanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The token travels in the body, not the URL, so it stays out of access logs. */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationAcceptanceController {

    private final InvitationAcceptanceService service;

    public InvitationAcceptanceController(InvitationAcceptanceService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public InvitationPreviewResponse preview(@Valid @RequestBody InvitationTokenRequest request) {
        return service.preview(request.token());
    }

    @PostMapping("/accept")
    public InvitationAcceptedResponse accept(Authentication authentication,
                                             @Valid @RequestBody InvitationTokenRequest request) {
        return service.accept(authentication.getName(), request.token());
    }

    @PostMapping("/accept-new")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationAcceptedResponse acceptNew(@Valid @RequestBody AcceptNewAccountRequest request) {
        return service.acceptWithNewAccount(request);
    }
}