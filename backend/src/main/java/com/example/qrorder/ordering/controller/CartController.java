package com.example.qrorder.ordering.controller;

import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.dining.service.CustomerSessionService;
import com.example.qrorder.ordering.dto.PriceCartRequest;
import com.example.qrorder.ordering.dto.PricedCart;
import com.example.qrorder.ordering.service.OrderPricingService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public to anyone holding a valid customer cookie. Prices a cart; saves nothing. */
@RestController
@RequestMapping("/api/v1/customer/cart")
public class CartController {

    private final CustomerSessionService customers;
    private final OrderPricingService pricing;

    public CartController(CustomerSessionService customers, OrderPricingService pricing) {
        this.customers = customers;
        this.pricing = pricing;
    }

    @PostMapping("/price")
    public ResponseEntity<PricedCart> price(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie,
            @Valid @RequestBody PriceCartRequest request) {
        PricedCart priced = pricing.price(customers.resolve(cookie), request.lines());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(priced);
    }
}