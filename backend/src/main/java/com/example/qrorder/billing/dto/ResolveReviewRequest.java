package com.example.qrorder.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveReviewRequest(@NotBlank @Size(min = 3, max = 200) String note) {
}