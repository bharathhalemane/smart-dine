package com.example.qrorder.menu.model;

/** One choice of "pick exactly one" (Half / Full). The price is a delta on the item's base price. */
public record MenuVariant(String id, String name, long priceDeltaMinor) {
}