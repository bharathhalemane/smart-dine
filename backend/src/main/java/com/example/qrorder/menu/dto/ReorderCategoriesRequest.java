package com.example.qrorder.menu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Every category id of the hotel, exactly once, in the new display order. */
public record ReorderCategoriesRequest(@NotEmpty @Size(max = 200) List<@NotBlank String> categoryIds) {
}