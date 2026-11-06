package com.example.qrorder.menu.dto;

import com.example.qrorder.menu.model.Diet;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateItemRequest(
        @NotBlank String categoryId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotNull @Min(0) @Max(10_000_000) Long priceMinor,
        Diet diet,
        @Min(0) @Max(10000) Integer sortOrder) {
}