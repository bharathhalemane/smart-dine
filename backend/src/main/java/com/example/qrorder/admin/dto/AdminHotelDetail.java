package com.example.qrorder.admin.dto;

import com.example.qrorder.tenancy.model.HotelStatus;
import java.time.Instant;

public record AdminHotelDetail(String id, String slug, String name, String address, String timezone,
                               String currency, HotelStatus status, String statusReason, OwnerInfo owner,
                               int staffCount, int tableCount, boolean waitersMayClearTables,
                               boolean cashiersMayAdjustBills, Instant createdAt, Instant updatedAt) {
}