package com.example.qrorder.tenancy.dto;

/** Partial update: a field left out (null) is not changed. */
public record UpdatePermissionSettingsRequest(Boolean waitersMayClearTables, Boolean cashiersMayAdjustBills) {
}