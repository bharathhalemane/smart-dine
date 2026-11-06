package com.example.qrorder.dining.controller;

import com.example.qrorder.dining.dto.FloorTableResponse;
import com.example.qrorder.dining.service.SessionQueryService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/floor")
public class FloorController {

    private final SessionQueryService queries;

    public FloorController(SessionQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<FloorTableResponse> floor(@CurrentTenant TenantContext tenant) {
        return queries.floor(tenant);
    }
}