package com.example.qrorder.tenancy.dto;

import jakarta.validation.constraints.Size;

/** Every field is optional; only the ones sent are changed. */
public record UpdateHotelRequest(
        @Size(max = 100) String name,
        @Size(max = 300) String address,
        @Size(max = 60) String timezone) {
}