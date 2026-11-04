package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.HotelRole;
import java.util.Set;

public record MyHotelResponse(HotelResponse hotel, Set<HotelRole> roles) {
}