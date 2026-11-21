package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.service.OnlinePaymentWebhookService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, like the per-hotel one: trust comes only from the signature over the raw body. */
@RestController
@RequestMapping("/api/v1/webhooks/razorpay-platform")
public class PlatformRazorpayWebhookController {

    private final OnlinePaymentWebhookService service;

    public PlatformRazorpayWebhookController(OnlinePaymentWebhookService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, String> receive(
            @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        service.handlePlatform(body, signature);
        return Map.of("status", "ok");
    }
}