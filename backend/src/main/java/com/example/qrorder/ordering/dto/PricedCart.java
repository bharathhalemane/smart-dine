package com.example.qrorder.ordering.dto;

import com.example.qrorder.ordering.model.OrderLine;
import java.util.List;

/** The server's answer: exactly what an order would contain if confirmed now. */
public record PricedCart(String currency, List<OrderLine> lines, long subtotalMinor, int totalQuantity) {
}