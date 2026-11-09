package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.PaymentReceipt;
import com.example.qrorder.billing.dto.PaymentResponse;
import com.example.qrorder.billing.dto.RecordPaymentRequest;
import com.example.qrorder.billing.dto.VoidPaymentRequest;
import com.example.qrorder.billing.service.PaymentService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 201 for a new payment, 200 for a replay of the same one. */
@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/sessions/{sessionId}/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentReceipt> record(@CurrentTenant TenantContext tenant,
                                                 @PathVariable("sessionId") String sessionId,
                                                 @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                 @Valid @RequestBody RecordPaymentRequest request) {
        PaymentService.Recorded result = service.record(tenant, sessionId, key, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.receipt());
    }

    @GetMapping
    public List<PaymentResponse> list(@CurrentTenant TenantContext tenant,
                                      @PathVariable("sessionId") String sessionId) {
        return service.list(tenant, sessionId);
    }

    @PostMapping("/{paymentId}/void")
    public PaymentReceipt voidPayment(@CurrentTenant TenantContext tenant,
                                      @PathVariable("sessionId") String sessionId,
                                      @PathVariable("paymentId") String paymentId,
                                      @Valid @RequestBody VoidPaymentRequest request) {
        return service.voidPayment(tenant, sessionId, paymentId, request.reason());
    }
}