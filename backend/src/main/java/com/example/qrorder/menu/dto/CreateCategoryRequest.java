package com.example.qrorder.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 300) String description,
        @Min(0) @Max(10000) Integer sortOrder) {
}