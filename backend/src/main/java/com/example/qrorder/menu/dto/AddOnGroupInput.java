package com.example.qrorder.menu.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AddOnGroupInput(
        String id,
        @NotBlank @Size(max = 40) String name,
        @NotNull @Min(0) @Max(20) Integer minSelect,
        @NotNull @Min(1) @Max(20) Integer maxSelect,
        @NotNull @Size(min = 1, max = 20) List<@Valid AddOnOptionInput> options) {
}