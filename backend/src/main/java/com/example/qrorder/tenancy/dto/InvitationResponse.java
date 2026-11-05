package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.InvitationStatus;
import com.example.qrorder.tenancy.model.StaffInvitation;
import java.time.Instant;
import java.util.Set;

/** Never includes the token or its hash. */
public record InvitationResponse(
        String id,
        String email,
        Set<HotelRole> roles,
        InvitationStatus status,
        Instant expiresAt,
        Instant createdAt) {

    public static InvitationResponse from(StaffInvitation i) {
        return new InvitationResponse(i.getId(), i.getEmail(), Set.copyOf(i.getRoles()), i.getStatus(),
                i.getExpiresAt(), i.getCreatedAt());
    }
}