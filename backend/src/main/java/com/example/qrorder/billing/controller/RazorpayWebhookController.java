package com.example.qrorder.billing.controller;

import com.example.qrorder.billing.service.OnlinePaymentWebhookService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public: Razorpay calls it with no login. Trust comes from the signature over the raw body, checked against
 * the secret of the hotel this address belongs to. The body is read as bytes so the signature stays valid.
 */
@RestController
@RequestMapping("/api/v1/webhooks/razorpay/{hookKey}")
public class RazorpayWebhookController {

    private final OnlinePaymentWebhookService service;

    public RazorpayWebhookController(OnlinePaymentWebhookService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, String> receive(@PathVariable("hookKey") String hookKey,
                                       @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestBody byte[] body) {
        service.handle(hookKey, body, signature);
        return Map.of("status", "ok");
    }
}