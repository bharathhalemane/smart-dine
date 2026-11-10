package com.example.qrorder.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** confirmSlug must equal the hotel's slug: a deliberate extra step for the most serious action. */
public record DeactivateHotelRequest(@NotBlank @Size(min = 3, max = 300) String reason,
                                     @NotBlank @Size(max = 100) String confirmSlug) {
}