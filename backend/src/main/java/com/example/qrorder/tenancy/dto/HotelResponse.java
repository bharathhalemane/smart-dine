package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelStatus;
import java.time.Instant;

public record HotelResponse(String id, String slug, String name, String address, String timezone,
                            String currency, HotelStatus status, Instant createdAt) {

    public static HotelResponse from(Hotel hotel) {
        return new HotelResponse(hotel.getId(), hotel.getSlug(), hotel.getName(), hotel.getAddress(),
                hotel.getTimezone(), hotel.getCurrency(), hotel.getStatus(), hotel.getCreatedAt());
    }
}