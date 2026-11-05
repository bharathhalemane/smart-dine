package com.example.qrorder.tenancy.dto;

import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.Permission;
import java.util.Set;

public record MyPermissionsResponse(Set<HotelRole> roles, Set<Permission> permissions) {
}