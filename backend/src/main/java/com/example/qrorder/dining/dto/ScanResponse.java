package com.example.qrorder.dining.dto;

import com.example.qrorder.dining.model.SessionStatus;

/** Deliberately minimal and public: no ids. Customer identity arrives with the customer session (Phase 7). */
public record ScanResponse(String hotelName, String tableNumber, String section,
                           SessionStatus sessionStatus, boolean newSession) {
}