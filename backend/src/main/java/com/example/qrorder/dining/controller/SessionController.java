package com.example.qrorder.dining.controller;

import com.example.qrorder.dining.dto.SessionDetailResponse;
import com.example.qrorder.dining.dto.SessionResponse;
import com.example.qrorder.dining.service.DiningSessionService;
import com.example.qrorder.dining.service.SessionLifecycleService;
import com.example.qrorder.dining.service.SessionQueryService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/sessions")
public class SessionController {

    private final DiningSessionService service;
    private final SessionQueryService queries;
    private final SessionLifecycleService lifecycle;

    public SessionController(DiningSessionService service, SessionQueryService queries, SessionLifecycleService lifecycle) {

        this.service = service;
        this.queries = queries;
        this.lifecycle = lifecycle;

    }

    @GetMapping
    public List<SessionResponse> active(@CurrentTenant TenantContext tenant) {
        return service.listActive(tenant);
    }

    @GetMapping("/{sessionId}")
    public SessionDetailResponse get(@CurrentTenant TenantContext tenant, @PathVariable("sessionId") String sessionId){
        return queries.get(tenant, sessionId);
    }

    @PostMapping("/{sessionId}/request-bill")
    public SessionDetailResponse requestBill(@CurrentTenant TenantContext tenant,
                                             @PathVariable("sessionId") String sessionId){
        return lifecycle.requestBill(tenant, sessionId);
    }

    @PostMapping("/{sessionId}/reopen")
    public SessionDetailResponse reopen(@CurrentTenant TenantContext tenant,
                                        @PathVariable("sessionId") String sessionId){
        return lifecycle.reopen(tenant, sessionId);
    }

    @PostMapping("/{sessionId}/close")
    public SessionDetailResponse close(@CurrentTenant TenantContext tenant,
                                       @PathVariable("sessionId") String sessionId) {
        return lifecycle.close(tenant, sessionId);
    }
}