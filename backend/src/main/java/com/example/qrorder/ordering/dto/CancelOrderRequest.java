package com.example.qrorder.ordering.dto;

import jakarta.validation.constraints.Size;

/** The reason is required, but checked in the service so a missing body gets the same clear 400. */
public record CancelOrderRequest(@Size(max = 100) String reason) {
}