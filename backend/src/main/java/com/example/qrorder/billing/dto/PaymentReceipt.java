package com.example.qrorder.billing.dto;

/** A payment and where the bill stands afterwards. balanceMinor is what is still owed. */
public record PaymentReceipt(PaymentResponse payment, String currency, long totalMinor, long paidMinor,
                             long balanceMinor, long overpaidMinor) {
}