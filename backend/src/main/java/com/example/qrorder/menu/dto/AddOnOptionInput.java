package com.example.qrorder.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddOnOptionInput(
        String id,
        @NotBlank @Size(max = 40) String name,
        @NotNull @Min(0) @Max(10_000_000) Long priceMinor) {
}