package com.example.qrorder.tables.dto;

import jakarta.validation.constraints.Size;

public record DeclineRequest(@Size(max = 200) String reason) {
}