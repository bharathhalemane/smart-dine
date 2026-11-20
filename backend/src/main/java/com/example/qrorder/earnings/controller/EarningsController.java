package com.example.qrorder.earnings.controller;

import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.earnings.dto.EarningsEntryView;
import com.example.qrorder.earnings.dto.EarningsSummary;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.service.EarningsReportService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/earnings")
public class EarningsController {

    private final EarningsReportService reports;

    public EarningsController(EarningsReportService reports) {
        this.reports = reports;
    }

    /** Totals and a day-by-day breakdown. Dates are in the hotel's timezone. Defaults to the last 30 days; at most 93 days. */
    @GetMapping
    public EarningsSummary summary(@CurrentTenant TenantContext tenant,
                                   @RequestParam(name = "from", required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(name = "to", required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(tenant, from, to);
    }

    /** The ledger lines, newest first. */
    @GetMapping("/entries")
    public PageResponse<EarningsEntryView> entries(@CurrentTenant TenantContext tenant,
                                                   @RequestParam(name = "type", required = false) EarningsEntryType type,
                                                   @RequestParam(name = "page", defaultValue = "0") int page,
                                                   @RequestParam(name = "size", defaultValue = "20") int size) {
        return reports.entries(tenant, type, page, size);
    }
}