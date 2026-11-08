package com.example.qrorder.tables.dto;

import jakarta.validation.constraints.Size;

public record ApproveRequest(@Size(max = 64) String toUserId) {
}