package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.OnlinePaymentStatus;

/** Everything the provider's checkout needs: open it with keyId and providerOrderId. */
public record OnlinePaymentStart(String attemptId, String provider, String providerOrderId, String keyId,
                                 long amountMinor, String currency, OnlinePaymentStatus status) {
}