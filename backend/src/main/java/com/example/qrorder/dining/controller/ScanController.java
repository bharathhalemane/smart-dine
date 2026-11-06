package com.example.qrorder.dining.controller;

import com.example.qrorder.dining.dto.ScanRequest;
import com.example.qrorder.dining.dto.ScanResponse;
import com.example.qrorder.dining.service.DiningSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public. The token goes in the body, not the URL, so it stays out of access logs. */
@RestController
@RequestMapping("/api/v1/scan")
public class ScanController {

    private final DiningSessionService service;

    public ScanController(DiningSessionService service) {
        this.service = service;
    }

    @PostMapping
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return service.scan(request.token());
    }
}