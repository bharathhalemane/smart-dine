package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.dto.BillResponse;
import com.example.qrorder.billing.service.BillService;
import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.dining.service.CustomerSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public to anyone holding a valid customer cookie. Shows the bill of the diner's own table. */
@RestController
@RequestMapping("/api/v1/customer/bill")
public class CustomerBillController {

    private final CustomerSessionService customers;
    private final BillService bills;

    public CustomerBillController(CustomerSessionService customers, BillService bills) {
        this.customers = customers;
        this.bills = bills;
    }

    @GetMapping
    public ResponseEntity<BillResponse> get(
            @CookieValue(name = CustomerCookies.NAME, required = false) String cookie) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bills.forCustomer(customers.resolve(cookie)));
    }
}