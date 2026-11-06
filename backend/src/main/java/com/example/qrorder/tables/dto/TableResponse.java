package com.example.qrorder.tables.dto;

import com.example.qrorder.tables.model.DiningTable;
import java.time.Instant;

public record TableResponse(String id, String number, int capacity, String section, boolean active, Instant createdAt) {

    public static TableResponse from(DiningTable t) {
        return new TableResponse(t.getId(), t.getNumber(), t.getCapacity(), t.getSection(), t.isActive(),
                t.getCreatedAt());
    }
}