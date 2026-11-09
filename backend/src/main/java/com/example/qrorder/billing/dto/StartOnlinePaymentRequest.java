package com.example.qrorder.billing.dto;

import jakarta.validation.constraints.Positive;

/** amountMinor left out means the whole balance. */
public record StartOnlinePaymentRequest(@Positive Long amountMinor) {
}