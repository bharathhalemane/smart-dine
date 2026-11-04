package com.example.qrorder.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(min = 10, max = 200, message = "must be at least 10 characters") String newPassword) {
}