package com.example.qrorder.media.controller;

import com.example.qrorder.media.dto.MediaResponse;
import com.example.qrorder.media.dto.UploadRequest;
import com.example.qrorder.media.dto.UploadTicket;
import com.example.qrorder.media.service.MediaService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadTicket requestUpload(@CurrentTenant TenantContext tenant, @Valid @RequestBody UploadRequest request) {
        return service.requestUpload(tenant, request);
    }

    @PostMapping("/{assetId}/complete")
    public MediaResponse complete(@CurrentTenant TenantContext tenant, @PathVariable("assetId") String assetId) {
        return service.complete(tenant, assetId);
    }
}