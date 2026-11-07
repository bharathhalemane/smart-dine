package com.example.qrorder.menu.dto;

import jakarta.validation.constraints.NotNull;

public record SetAvailabilityRequest(@NotNull Boolean available) {
}