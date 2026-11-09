package com.example.qrorder.billing.dto;

/** changed is false when the bill already agreed with the ledger. */
public record RepairResult(String billId, boolean changed, long beforeMinor, long afterMinor) {
}