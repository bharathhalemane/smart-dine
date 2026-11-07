package com.example.qrorder.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** id is optional: send an existing id to keep that variant (renaming it), leave it out to create one. */
public record VariantInput(
        String id,
        @NotBlank @Size(max = 40) String name,
        @NotNull @Min(-10_000_000) @Max(10_000_000) Long priceDeltaMinor) {
}