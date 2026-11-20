package com.example.qrorder.admin.controller;

import com.example.qrorder.admin.dto.CommissionResponse;
import com.example.qrorder.admin.dto.UpdateCommissionRequest;
import com.example.qrorder.admin.service.CommissionAdminService;
import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.earnings.dto.EarningsSummary;
import com.example.qrorder.earnings.dto.HotelBalanceRow;
import com.example.qrorder.earnings.service.EarningsReportService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin only: /api/v1/admin/** is restricted in SecurityConfig. Uses {id}, not {hotelId}, on purpose. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEarningsController {

    private final EarningsReportService reports;
    private final CommissionAdminService commissions;

    public AdminEarningsController(EarningsReportService reports, CommissionAdminService commissions) {
        this.reports = reports;
        this.commissions = commissions;
    }

    /** What the platform owes each hotel, largest first. */
    @GetMapping("/earnings")
    public PageResponse<HotelBalanceRow> balances(@RequestParam(name = "page", defaultValue = "0") int page,
                                                  @RequestParam(name = "size", defaultValue = "20") int size) {
        return reports.balances(page, size);
    }

    @GetMapping("/hotels/{id}/earnings")
    public EarningsSummary hotelEarnings(@PathVariable("id") String id,
                                         @RequestParam(name = "from", required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(name = "to", required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summaryFor(id, from, to);
    }

    @GetMapping("/hotels/{id}/commission")
    public CommissionResponse commission(@PathVariable("id") String id) {
        return commissions.get(id);
    }

    @PutMapping("/hotels/{id}/commission")
    public CommissionResponse setCommission(Authentication who, @PathVariable("id") String id,
                                            @Valid @RequestBody UpdateCommissionRequest request) {
        return commissions.update(who.getName(), id, request.commissionBps(), request.reason());
    }
}