package com.example.qrorder.tables.controller;

import com.example.qrorder.tables.dto.QrSheetRequest;
import com.example.qrorder.tables.service.QrSheetService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/qr-sheet")
public class QrSheetController {

    private final QrSheetService service;

    public QrSheetController(QrSheetService service) {
        this.service = service;
    }

    /** POST because it has a side effect: it issues new credentials. Returns a ready-to-print HTML page. */
    @PostMapping
    public ResponseEntity<String> generate(@CurrentTenant TenantContext tenant,
                                           @Valid @RequestBody(required = false) QrSheetRequest request) {
        String html = service.generate(tenant, request == null ? null : request.tableIds());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
                .header("Content-Security-Policy",
                        "default-src 'none'; img-src data:; style-src 'unsafe-inline'")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }
}