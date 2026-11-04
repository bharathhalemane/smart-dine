package com.example.qrorder.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateHotelRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 300) String address,
        @Size(max = 60) String timezone) {
}