package com.example.qrorder.ordering.service;

import com.example.qrorder.ordering.model.OrderStatus;

/**
 * Lets another module decide what state a new order starts in, without ordering knowing about it.
 * Billing answers AWAITING_PAYMENT for hotels where guests pay first. Every other case answers PENDING.
 */
public interface OrderAdmissionPolicy {

    OrderStatus initialStatus(String hotelId);
}