package com.example.qrorder.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InvitationTokenRequest(@NotBlank @Size(max = 200) String token) {
}