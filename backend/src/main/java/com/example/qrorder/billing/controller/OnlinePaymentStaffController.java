package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.OnlinePaymentView;
import com.example.qrorder.billing.dto.ResolveReviewRequest;
import com.example.qrorder.billing.service.OnlinePaymentReviewService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/payments/online")
public class OnlinePaymentStaffController {

    private final OnlinePaymentReviewService service;

    public OnlinePaymentStaffController(OnlinePaymentReviewService service) {
        this.service = service;
    }

    @GetMapping("/review")
    public List<OnlinePaymentView> review(@CurrentTenant TenantContext tenant) {
        return service.needingReview(tenant);
    }

    @PostMapping("/{attemptId}/resolve")
    public OnlinePaymentView resolve(@CurrentTenant TenantContext tenant,
                                     @PathVariable("attemptId") String attemptId,
                                     @Valid @RequestBody ResolveReviewRequest request) {
        return service.resolve(tenant, attemptId, request.note());
    }
}