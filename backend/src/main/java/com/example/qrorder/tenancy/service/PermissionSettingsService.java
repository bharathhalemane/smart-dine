package com.example.qrorder.tenancy.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.PermissionSettingsResponse;
import com.example.qrorder.tenancy.dto.UpdatePermissionSettingsRequest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.repository.HotelRepository;
import org.springframework.stereotype.Service;

@Service
public class PermissionSettingsService {

    private final HotelRepository hotels;

    public PermissionSettingsService(HotelRepository hotels) {
        this.hotels = hotels;
    }

    public PermissionSettingsResponse get(TenantContext tenant) {
        tenant.require(Permission.MANAGE_STAFF);
        return toResponse(load(tenant));
    }

    public PermissionSettingsResponse update(TenantContext tenant, UpdatePermissionSettingsRequest request) {
        tenant.require(Permission.MANAGE_HOTEL); // owner only
        Hotel hotel = load(tenant);
        if (request.waitersMayClearTables() != null) {
            hotel.setWaitersMayClearTables(request.waitersMayClearTables());
        }
        if (request.cashiersMayAdjustBills() != null) {
            hotel.setCashiersMayAdjustBills(request.cashiersMayAdjustBills());
        }
        return toResponse(hotels.save(hotel));
    }

    private Hotel load(TenantContext tenant) {
        return hotels.findById(tenant.hotelId()).orElseThrow(() -> DomainException.notFound("Hotel"));
    }

    private static PermissionSettingsResponse toResponse(Hotel hotel) {
        return new PermissionSettingsResponse(hotel.isWaitersMayClearTables(), hotel.isCashiersMayAdjustBills());
    }
}