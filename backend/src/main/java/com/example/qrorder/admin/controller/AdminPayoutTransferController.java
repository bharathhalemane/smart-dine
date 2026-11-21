package com.example.qrorder.admin.controller;

import com.example.qrorder.admin.dto.RetryPayoutRequest;
import com.example.qrorder.admin.service.PayoutRetryService;
import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.payout.dto.PayoutTransferView;
import com.example.qrorder.payout.model.TransferStatus;
import com.example.qrorder.payout.service.PayoutQueryService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin only: /api/v1/admin/** is restricted in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/payouts")
public class AdminPayoutTransferController {

    private final PayoutQueryService queries;
    private final PayoutRetryService retries;

    public AdminPayoutTransferController(PayoutQueryService queries, PayoutRetryService retries) {
        this.queries = queries;
        this.retries = retries;
    }

    /** Every hotel's transfers. Filter with ?status=NEEDS_REVIEW to see what a person must look at. */
    @GetMapping
    public PageResponse<PayoutTransferView> list(@RequestParam(name = "status", required = false) TransferStatus status,
                                                 @RequestParam(name = "page", defaultValue = "0") int page,
                                                 @RequestParam(name = "size", defaultValue = "20") int size) {
        return queries.all(status, page, size);
    }

    @PostMapping("/{id}/retry")
    public PayoutTransferView retry(Authentication who, @PathVariable("id") String id,
                                    @Valid @RequestBody RetryPayoutRequest request) {
        return retries.retry(who.getName(), id, request.reason(), Boolean.TRUE.equals(request.confirmNotSent()));
    }
}