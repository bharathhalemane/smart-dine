package com.example.qrorder.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** commissionBps: 100 = 1%. Up to 3000 (30%). */
public record UpdateCommissionRequest(@NotNull @Min(0) @Max(3000) Integer commissionBps,
                                      @NotBlank @Size(min = 3, max = 300) String reason) {
}