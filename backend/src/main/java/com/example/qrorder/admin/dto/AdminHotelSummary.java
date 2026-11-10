package com.example.qrorder.admin.dto;

import com.example.qrorder.tenancy.model.HotelStatus;
import java.time.Instant;

public record AdminHotelSummary(String id, String slug, String name, String address, HotelStatus status,
                                String statusReason, OwnerInfo owner, int staffCount, int tableCount,
                                Instant createdAt) {
}