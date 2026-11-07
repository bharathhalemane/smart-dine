package com.example.qrorder.ordering.model;

/** A chosen add-on, copied at order time. */
public record OrderOption(String groupId, String groupName, String optionId, String optionName, long priceMinor) {
}