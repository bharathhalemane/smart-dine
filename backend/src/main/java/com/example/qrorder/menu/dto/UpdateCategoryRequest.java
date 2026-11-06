package com.example.qrorder.menu.dto;

import jakarta.validation.constraints.Size;

/** Partial update: a field left out (null) is not changed. An empty description clears it. */
public record UpdateCategoryRequest(
        @Size(max = 60) String name,
        @Size(max = 300) String description,
        Boolean active) {
}