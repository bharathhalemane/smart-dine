package com.example.qrorder.payout.model;

public enum TransferStatus {
    PENDING,       // waiting to be sent (no payout account yet, or not tried yet, or Razorpay was unreachable)
    SENDING,       // claimed by one server right now
    SENT,          // Razorpay accepted it; waiting for its processed webhook (2b)
    PROCESSED,     // Razorpay confirmed the money reached the hotel's linked account
    FAILED,        // Razorpay refused it: nothing was transferred
    NEEDS_REVIEW,  // we cannot tell whether Razorpay received it: a person checks the dashboard
    SKIPPED        // nothing to send (the hotel's share was below Razorpay's minimum or zero)
}