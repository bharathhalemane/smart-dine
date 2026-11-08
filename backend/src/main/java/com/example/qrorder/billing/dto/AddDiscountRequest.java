package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.DiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** value: basis points for PERCENT (1000 = 10%), paise for FLAT. A reason is always required. */
public record AddDiscountRequest(@NotNull DiscountType type, @Positive long value,
                                 @NotBlank @Size(min = 3, max = 200) String reason) {
}