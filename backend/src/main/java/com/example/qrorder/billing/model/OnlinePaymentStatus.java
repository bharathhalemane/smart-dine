package com.example.qrorder.billing.model;

public enum OnlinePaymentStatus {
    CREATED,       // order exists at the provider, nothing paid yet
    CAPTURED,      // paid and counted on the bill
    FAILED,        // the provider reported a failed attempt
    NEEDS_REVIEW,  // money was captured but could not be counted: a refund or decision is due
    RESOLVED ,
    EXPIRED    // staff dealt with a NEEDS_REVIEW entry
}