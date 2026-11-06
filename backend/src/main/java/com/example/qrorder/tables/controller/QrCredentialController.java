package com.example.qrorder.tables.controller;

import com.example.qrorder.tables.dto.IssuedQrResponse;
import com.example.qrorder.tables.dto.QrStatusResponse;
import com.example.qrorder.tables.service.QrCredentialService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/tables/{tableId}/qr")
public class QrCredentialController {

    private final QrCredentialService service;

    public QrCredentialController(QrCredentialService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedQrResponse issue(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId) {
        return service.issue(tenant, tableId);
    }

    @GetMapping
    public QrStatusResponse status(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId) {
        return service.status(tenant, tableId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId) {
        service.revoke(tenant, tableId);
    }
}