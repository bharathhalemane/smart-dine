package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.HotelRole;
import java.time.Instant;
import java.util.Set;

public record InvitationPreviewResponse(
        String hotelName,
        String email,
        Set<HotelRole> roles,
        boolean accountExists,
        Instant expiresAt) {
}