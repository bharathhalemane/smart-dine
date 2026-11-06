package com.example.qrorder.tenancy.dto;

/** The little that other modules may know about a hotel without a tenant context. */
public record PublicHotelInfo(String id, String name) {
}