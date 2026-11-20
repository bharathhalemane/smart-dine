package com.example.qrorder.admin.dto;

/** custom = false means the platform default applies because no rate has been set for this hotel. */
public record CommissionResponse(String hotelId, int commissionBps, boolean custom) {
}