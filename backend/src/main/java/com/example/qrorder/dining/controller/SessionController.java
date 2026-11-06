package com.example.qrorder.dining.controller;

import com.example.qrorder.dining.dto.SessionResponse;
import com.example.qrorder.dining.service.DiningSessionService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/sessions")
public class SessionController {

    private final DiningSessionService service;

    public SessionController(DiningSessionService service) {
        this.service = service;
    }

    @GetMapping
    public List<SessionResponse> active(@CurrentTenant TenantContext tenant) {
        return service.listActive(tenant);
    }
}