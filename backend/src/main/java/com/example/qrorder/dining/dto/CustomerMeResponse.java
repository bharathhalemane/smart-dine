package com.example.qrorder.dining.dto;

import com.example.qrorder.dining.model.SessionStatus;

/** What the customer's own device may know about where it is. No internal ids. */
public record CustomerMeResponse(String hotelName, String tableNumber, SessionStatus sessionStatus) {
}