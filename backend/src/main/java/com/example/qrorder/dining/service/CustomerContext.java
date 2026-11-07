package com.example.qrorder.dining.service;

import com.example.qrorder.dining.model.SessionStatus;

/**
 * Who the customer is, as proven by their cookie. Phases 7.3 and 7.4 take hotel, table and session
 * from here, never from anything the client sends.
 */
public record CustomerContext(String customerSessionId, String hotelId, String tableId, String tableNumber,
                              String diningSessionId, SessionStatus sessionStatus) {
}