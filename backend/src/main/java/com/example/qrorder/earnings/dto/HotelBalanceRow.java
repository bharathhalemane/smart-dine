package com.example.qrorder.earnings.dto;

public record HotelBalanceRow(String hotelId, String hotelName, String slug, String currency, int commissionBps,
                              long collectedMinor, long payoutsMinor, long balanceMinor) {
}