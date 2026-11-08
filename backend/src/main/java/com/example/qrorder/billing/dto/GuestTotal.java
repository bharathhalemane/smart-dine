package com.example.qrorder.billing.dto;

/** What one guest at the table ordered and had delivered. */
public record GuestTotal(String label, long subtotalMinor) {
}