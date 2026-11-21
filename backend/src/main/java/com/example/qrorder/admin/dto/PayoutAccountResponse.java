package com.example.qrorder.admin.dto;

/** linkedAccountId is null when none is set. */
public record PayoutAccountResponse(String hotelId, String linkedAccountId) {
}