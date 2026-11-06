package com.example.qrorder.tables.service;

/** Implemented by the dining module. Lets table management ask "is this table in use?" without depending on it. */
public interface ActiveSessionGuard {

    boolean hasActiveSession(String hotelId, String tableId);
}