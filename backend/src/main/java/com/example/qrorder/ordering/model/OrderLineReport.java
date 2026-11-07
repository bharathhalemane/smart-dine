package com.example.qrorder.ordering.model;

import java.time.Instant;

/** Audit record: who reported which line unavailable, why, and when. */
public record OrderLineReport(int lineNo, String itemName, String reason, String reportedBy, Instant at) {
}