package com.example.qrorder.billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateHoldSettingsRequest(@NotNull @Min(5) @Max(180) Integer holdMinutes) {
}