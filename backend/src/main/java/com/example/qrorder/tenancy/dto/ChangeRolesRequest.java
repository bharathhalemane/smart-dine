package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.HotelRole;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record ChangeRolesRequest(@NotEmpty Set<@NotNull HotelRole> roles) {
}