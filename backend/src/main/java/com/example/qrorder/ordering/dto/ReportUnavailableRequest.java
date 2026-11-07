package com.example.qrorder.ordering.dto;

import jakarta.validation.constraints.Size;

/** Both fields are optional. alsoMarkMenuItem defaults to true. */
public record ReportUnavailableRequest(@Size(max = 100) String reason, Boolean alsoMarkMenuItem) {
}