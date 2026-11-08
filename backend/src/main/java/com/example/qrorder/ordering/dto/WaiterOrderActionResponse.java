package com.example.qrorder.ordering.dto;

import com.example.qrorder.ordering.model.OrderStatus;
import java.time.Instant;

/** The result of a pickup or delivery: where the order is now and who (if anyone) is carrying it. */
public record WaiterOrderActionResponse(String orderId, OrderStatus status, String tableNumber, String customerLabel,
                                        String pickedUpByUserId, String pickedUpByName, Instant pickedUpAt) {
}