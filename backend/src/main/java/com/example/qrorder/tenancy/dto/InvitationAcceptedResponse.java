package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.HotelRole;
import java.util.Set;

public record InvitationAcceptedResponse(String hotelId, String hotelName, Set<HotelRole> roles) {
}