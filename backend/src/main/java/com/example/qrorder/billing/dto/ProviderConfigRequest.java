package com.example.qrorder.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** keyId looks like rzp_test_xxxx or rzp_live_xxxx. The webhook secret is the one set in Razorpay's webhook page. */
public record ProviderConfigRequest(
        @NotBlank @Pattern(regexp = "^rzp_(test|live)_[A-Za-z0-9]{6,40}$",
                message = "must look like rzp_test_xxxxxx or rzp_live_xxxxxx") String keyId,
        @NotBlank @Size(min = 6, max = 128) String keySecret,
        @NotBlank @Size(min = 6, max = 128) String webhookSecret) {
}