package com.example.qrorder.tenancy.event;

import com.example.qrorder.tenancy.model.HotelStatus;

/** A hotel's status was changed (and committed). */
public record HotelStatusChanged(String hotelId, HotelStatus from, HotelStatus to) {
}