package com.example.qrorder.tenancy.service;

import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.Permission;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The one place that decides which role gets which permission. */
public final class PermissionResolver {

    private static final Map<HotelRole, Set<Permission>> BASE = new EnumMap<>(HotelRole.class);

    static {
        BASE.put(HotelRole.OWNER, EnumSet.allOf(Permission.class));
        BASE.put(HotelRole.MANAGER, EnumSet.complementOf(EnumSet.of(Permission.MANAGE_HOTEL)));
        BASE.put(HotelRole.KITCHEN, EnumSet.of(Permission.KITCHEN_OPERATE, Permission.TOGGLE_AVAILABILITY));
        BASE.put(HotelRole.WAITER, EnumSet.of(Permission.DELIVER_ORDERS));
        BASE.put(HotelRole.CASHIER, EnumSet.of(Permission.TAKE_PAYMENT));
    }

    private PermissionResolver() {}

    public static Set<Permission> resolve(Set<HotelRole> roles, Hotel hotel) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        roles.forEach(role -> result.addAll(BASE.get(role)));

        // Per-hotel switches. They extend only the role they are named for.
        if (roles.contains(HotelRole.WAITER) && hotel.isWaitersMayClearTables()) {
            result.add(Permission.CLEAR_TABLE);
        }
        if (roles.contains(HotelRole.CASHIER) && hotel.isCashiersMayAdjustBills()) {
            result.add(Permission.ADJUST_BILL);
        }
        return result;
    }
}