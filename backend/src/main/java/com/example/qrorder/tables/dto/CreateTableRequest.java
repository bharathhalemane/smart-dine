package com.example.qrorder.tables.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTableRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 _-]{0,19}$",
                message = "up to 20 letters, digits, spaces, dashes or underscores")
        String number,
        @NotNull @Min(1) @Max(50) Integer capacity,
        @Size(max = 50) String section) {
}