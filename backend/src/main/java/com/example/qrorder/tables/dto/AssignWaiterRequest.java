package com.example.qrorder.tables.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignWaiterRequest(@NotBlank @Size(max = 64) String userId) {
}