package com.example.qrorder.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HotelActionRequest(@NotBlank @Size(min = 3, max = 300) String reason) {
}