package com.example.qrorder.billing.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateSettlementSettingsRequest(@NotNull Boolean requireSettlementBeforeClear) {
}