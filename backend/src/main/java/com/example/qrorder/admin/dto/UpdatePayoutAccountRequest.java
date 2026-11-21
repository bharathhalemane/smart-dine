package com.example.qrorder.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** linkedAccountId is the acc_... id from the Razorpay dashboard (Route, Linked accounts). */
public record UpdatePayoutAccountRequest(
        @NotBlank @Pattern(regexp = "acc_[A-Za-z0-9]{8,40}", message = "must look like acc_XXXXXXXXXXXXXX") String linkedAccountId,
        @NotBlank @Size(min = 3, max = 300) String reason) {
}