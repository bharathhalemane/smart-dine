package com.example.qrorder.billing.model;

/** How money can be received. Online payment through a provider is added in step 10.6. */
public enum PaymentMethod {
    CASH,
    UPI,
    CARD,
    ONLINE
}