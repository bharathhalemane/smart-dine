package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.OnlinePaymentStart;
import com.example.qrorder.billing.dto.OnlinePaymentView;
import com.example.qrorder.billing.dto.StartOnlinePaymentRequest;
import com.example.qrorder.billing.service.OnlinePaymentService;
import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.dining.service.CustomerSessionService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public to anyone holding a valid customer cookie. 201 for a new attempt, 200 for a replay of the same one. */
@RestController
@RequestMapping("/api/v1/customer/payments/online")
public class CustomerOnlinePaymentController {

    private final CustomerSessionService customers;
    private final OnlinePaymentService service;

    public CustomerOnlinePaymentController(CustomerSessionService customers, OnlinePaymentService service) {
        this.customers = customers;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OnlinePaymentStart> start(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody(required = false) StartOnlinePaymentRequest request) {
        OnlinePaymentService.Started result = service.start(customers.resolve(cookie), key, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(result.body());
    }

    @GetMapping("/{attemptId}")
    public ResponseEntity<OnlinePaymentView> get(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie,
            @PathVariable("attemptId") String attemptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.get(customers.resolve(cookie), attemptId));
    }
}