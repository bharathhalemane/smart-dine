package com.example.qrorder.billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateBillingSettingsRequest(@NotBlank @Size(max = 20) String taxName,
                                           @NotNull @Min(0) @Max(5000) Integer taxRateBps,
                                           @NotNull Boolean taxInclusive,
                                           @NotNull @Min(0) @Max(3000) Integer serviceChargeBps) {
}