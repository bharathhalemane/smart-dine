package com.example.qrorder.tables.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Optional. Missing or empty means "every active table". */
public record QrSheetRequest(@Size(max = 100) List<@NotBlank String> tableIds) {
}