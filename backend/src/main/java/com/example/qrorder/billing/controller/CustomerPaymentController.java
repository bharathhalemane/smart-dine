package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.CustomerPaymentOptions;
import com.example.qrorder.billing.service.PaymentOptionsService;
import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.dining.service.CustomerSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public to anyone holding a valid customer cookie. */
@RestController
@RequestMapping("/api/v1/customer/payment-options")
public class CustomerPaymentController {

    private final CustomerSessionService customers;
    private final PaymentOptionsService options;

    public CustomerPaymentController(CustomerSessionService customers, PaymentOptionsService options) {
        this.customers = customers;
        this.options = options;
    }

    @GetMapping
    public ResponseEntity<CustomerPaymentOptions> get(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options.forCustomer(customers.resolve(cookie)));
    }
}