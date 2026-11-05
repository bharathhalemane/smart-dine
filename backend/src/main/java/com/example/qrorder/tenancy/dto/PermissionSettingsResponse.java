package com.example.qrorder.tenancy.dto;

public record PermissionSettingsResponse(boolean waitersMayClearTables, boolean cashiersMayAdjustBills) {
}