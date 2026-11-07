package com.example.qrorder.dining.controller;

import com.example.qrorder.dining.dto.ScanRequest;
import com.example.qrorder.dining.dto.ScanResponse;
import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.dining.service.CustomerScanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public. The token goes in the body, not the URL, so it stays out of access logs. */
@RestController
@RequestMapping("/api/v1/scan")
public class ScanController {

    private final CustomerScanService service;
    private final CustomerCookies cookies;


    public ScanController(CustomerScanService service, CustomerCookies cookies) {

        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping
    public ResponseEntity<ScanResponse> scan(@Valid @RequestBody ScanRequest request,
                             @CookieValue(name = CustomerCookies.NAME, required = false) String cookie) {
        CustomerScanService.Outcome outcome = service.scan(request.token(), cookie);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.newCustomerToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, cookies.issue(outcome.newCustomerToken()).toString());
        }
        return response.body(outcome.response());
    }
}