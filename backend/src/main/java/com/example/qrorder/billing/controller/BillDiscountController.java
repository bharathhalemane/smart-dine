package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.AddDiscountRequest;
import com.example.qrorder.billing.dto.BillResponse;
import com.example.qrorder.billing.service.BillService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/sessions/{sessionId}/bill/discounts")
public class BillDiscountController {

    private final BillService service;

    public BillDiscountController(BillService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BillResponse add(@CurrentTenant TenantContext tenant, @PathVariable("sessionId") String sessionId,
                            @Valid @RequestBody AddDiscountRequest request) {
        return service.addDiscount(tenant, sessionId, request);
    }

    @DeleteMapping("/{discountId}")
    public BillResponse remove(@CurrentTenant TenantContext tenant, @PathVariable("sessionId") String sessionId,
                               @PathVariable("discountId") String discountId) {
        return service.removeDiscount(tenant, sessionId, discountId);
    }
}