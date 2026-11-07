package com.example.qrorder.dining.dto;

import jakarta.validation.constraints.Size;

/** name may be null or blank, which clears it. The real rules live in CustomerNames. */
public record SetNameRequest(@Size(max = 200) String name) {
}