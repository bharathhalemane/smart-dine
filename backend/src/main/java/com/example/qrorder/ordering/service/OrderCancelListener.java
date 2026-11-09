package com.example.qrorder.ordering.service;

/**
 * Lets another module react when an order is cancelled, without ordering knowing about it.
 * Billing uses it: the money held for a cancelled order may now be enough to release the next one.
 */
public interface OrderCancelListener {

    void afterCancelled(String hotelId, String diningSessionId);
}