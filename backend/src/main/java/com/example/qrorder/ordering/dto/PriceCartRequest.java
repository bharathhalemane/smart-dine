package com.example.qrorder.ordering.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PriceCartRequest(@NotNull @Size(min = 1, max = 50) List<@Valid CartLine> lines) {
}