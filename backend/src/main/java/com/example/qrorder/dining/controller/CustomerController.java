package com.example.qrorder.dining.controller;

import com.example.qrorder.dining.dto.CustomerMeResponse;
import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.dining.service.CustomerSessionService;
import com.example.qrorder.dining.dto.SetNameRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public routes for diners. Identity comes from the cookie, not from Spring Security. */
@RestController
@RequestMapping("/api/v1/customer")
public class CustomerController {

    private final CustomerSessionService service;

    public CustomerController(CustomerSessionService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public ResponseEntity<CustomerMeResponse> me(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.me(cookie));
    }

    @PutMapping("/me/name")
    public ResponseEntity<CustomerMeResponse> setName(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie,
            @Valid @RequestBody SetNameRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.updateName(cookie, request.name()));
    }
}