package com.example.qrorder.billing.dto;

/** When true, a table can only be cleared once its bill is settled (or a manager forces it). */
public record SettlementSettingsResponse(boolean requireSettlementBeforeClear) {
}