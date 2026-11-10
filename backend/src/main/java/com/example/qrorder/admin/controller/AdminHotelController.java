package com.example.qrorder.admin.controller;

import com.example.qrorder.admin.dto.AdminHotelDetail;
import com.example.qrorder.admin.dto.AdminHotelSummary;
import com.example.qrorder.admin.service.AdminHotelService;
import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.tenancy.model.HotelStatus;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin only: /api/v1/admin/** is restricted in SecurityConfig. Uses {id}, not {hotelId}, on purpose. */
@RestController
@RequestMapping("/api/v1/admin/hotels")
public class AdminHotelController {

    private final AdminHotelService service;

    public AdminHotelController(AdminHotelService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AdminHotelSummary> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) HotelStatus status,
            @RequestParam(name = "createdAfter", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @RequestParam(name = "createdBefore", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "createdAt,desc") String sort) {
        return service.search(q, status, createdAfter, createdBefore, page, size, sort);
    }

    @GetMapping("/{id}")
    public AdminHotelDetail get(@PathVariable("id") String id) {
        return service.get(id);
    }
}