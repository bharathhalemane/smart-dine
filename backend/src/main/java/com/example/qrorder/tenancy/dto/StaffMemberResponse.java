package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.HotelRole;
import java.time.Instant;
import java.util.Set;

public record StaffMemberResponse(String userId, String name, String email, Set<HotelRole> roles, Instant joinedAt) {
}