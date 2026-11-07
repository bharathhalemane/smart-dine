package com.example.qrorder.ordering.controller;

import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.dining.service.CustomerSessionService;
import com.example.qrorder.ordering.dto.OrderResponse;
import com.example.qrorder.ordering.dto.PriceCartRequest;
import com.example.qrorder.ordering.service.OrderPlacementService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public to anyone holding a valid customer cookie. 201 for a new order, 200 for a replay of the same one. */
@RestController
@RequestMapping("/api/v1/customer/orders")
public class OrderController {

    private final CustomerSessionService customers;
    private final OrderPlacementService placement;

    public OrderController(CustomerSessionService customers, OrderPlacementService placement) {
        this.customers = customers;
        this.placement = placement;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PriceCartRequest request) {
        OrderPlacementService.Placement result =
                placement.place(customers.resolve(cookie), idempotencyKey, request.lines());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(OrderResponse.from(result.order()));
    }
}