package com.example.qrorder.payout.controller;

import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.payout.dto.PayoutTransferView;
import com.example.qrorder.payout.model.TransferStatus;
import com.example.qrorder.payout.service.PayoutQueryService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/payouts")
public class PayoutController {

    private final PayoutQueryService service;

    public PayoutController(PayoutQueryService service) {
        this.service = service;
    }

    /** The transfers of this hotel's share, newest first. */
    @GetMapping
    public PageResponse<PayoutTransferView> list(@CurrentTenant TenantContext tenant,
                                                 @RequestParam(name = "status", required = false) TransferStatus status,
                                                 @RequestParam(name = "page", defaultValue = "0") int page,
                                                 @RequestParam(name = "size", defaultValue = "20") int size) {
        return service.forHotel(tenant, status, page, size);
    }
}