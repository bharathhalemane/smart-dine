package com.example.qrorder.menu.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplaceVariantsRequest(@NotNull @Size(max = 20) List<@Valid VariantInput> variants) {
}