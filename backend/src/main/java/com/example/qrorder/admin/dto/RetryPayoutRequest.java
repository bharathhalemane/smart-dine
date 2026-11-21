package com.example.qrorder.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** confirmNotSent must be true to retry a transfer in NEEDS_REVIEW: you checked the Razorpay dashboard and nothing was sent. */
public record RetryPayoutRequest(@NotBlank @Size(min = 3, max = 300) String reason, Boolean confirmNotSent) {
}