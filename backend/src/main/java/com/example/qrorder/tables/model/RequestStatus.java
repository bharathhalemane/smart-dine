package com.example.qrorder.tables.model;

/** OPEN until decided. VOID means an approval could not be applied because the table changed hands. */
public enum RequestStatus {
    OPEN,
    ACCEPTED,
    APPROVED,
    DECLINED,
    CANCELLED,
    VOID
}