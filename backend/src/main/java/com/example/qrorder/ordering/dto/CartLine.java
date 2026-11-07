package com.example.qrorder.ordering.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** What the client may say: which item, which choices, how many. Never a price. */
public record CartLine(
        @NotBlank @Size(max = 64) String itemId,
        @Size(max = 64) String variantId,
        @Size(max = 20) List<@NotBlank @Size(max = 64) String> optionIds,
        @NotNull @Min(1) @Max(20) Integer quantity,
        @Size(max = 200) String note) {
}