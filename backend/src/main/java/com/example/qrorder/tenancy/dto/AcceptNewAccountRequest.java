package com.example.qrorder.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptNewAccountRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(min = 10, message = "must be at least 10 characters") String password) {
}