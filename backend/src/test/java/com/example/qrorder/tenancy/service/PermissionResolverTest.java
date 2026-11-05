package com.example.qrorder.tenancy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionResolverTest {

    private static Hotel hotel(boolean waitersClear, boolean cashiersAdjust) {
        Hotel hotel = new Hotel("s", "Name", "owner", null, "Asia/Kolkata", "INR", Instant.now());
        hotel.setWaitersMayClearTables(waitersClear);
        hotel.setCashiersMayAdjustBills(cashiersAdjust);
        return hotel;
    }

    private static Set<Permission> resolve(Hotel hotel, HotelRole... roles) {
        return PermissionResolver.resolve(EnumSet.copyOf(Set.of(roles)), hotel);
    }

    @Test
    void ownerHasEveryPermission() {
        assertThat(resolve(hotel(false, false), HotelRole.OWNER)).isEqualTo(EnumSet.allOf(Permission.class));
    }

    @Test
    void managerHasEverythingExceptManageHotel() {
        assertThat(resolve(hotel(false, false), HotelRole.MANAGER))
                .contains(Permission.MANAGE_STAFF, Permission.ADJUST_BILL, Permission.CLEAR_TABLE)
                .doesNotContain(Permission.MANAGE_HOTEL);
    }

    @Test
    void kitchenOnlyWorksTheKitchenAndAvailability() {
        assertThat(resolve(hotel(true, true), HotelRole.KITCHEN))
                .containsExactlyInAnyOrder(Permission.KITCHEN_OPERATE, Permission.TOGGLE_AVAILABILITY);
    }

    @Test
    void waiterClearsTablesOnlyWhenTheHotelAllowsIt() {
        assertThat(resolve(hotel(false, false), HotelRole.WAITER))
                .containsExactly(Permission.DELIVER_ORDERS);
        assertThat(resolve(hotel(true, false), HotelRole.WAITER))
                .containsExactlyInAnyOrder(Permission.DELIVER_ORDERS, Permission.CLEAR_TABLE);
    }

    @Test
    void cashierAdjustsBillsOnlyWhenTheHotelAllowsIt() {
        assertThat(resolve(hotel(false, false), HotelRole.CASHIER)).containsExactly(Permission.TAKE_PAYMENT);
        assertThat(resolve(hotel(false, true), HotelRole.CASHIER))
                .containsExactlyInAnyOrder(Permission.TAKE_PAYMENT, Permission.ADJUST_BILL);
    }

    @Test
    void aSwitchNeverLeaksToOtherRoles() {
        assertThat(resolve(hotel(true, true), HotelRole.CASHIER)).doesNotContain(Permission.CLEAR_TABLE);
        assertThat(resolve(hotel(true, true), HotelRole.WAITER)).doesNotContain(Permission.ADJUST_BILL);
    }

    @Test
    void multipleRolesCombine() {
        assertThat(resolve(hotel(false, false), HotelRole.WAITER, HotelRole.CASHIER))
                .containsExactlyInAnyOrder(Permission.DELIVER_ORDERS, Permission.TAKE_PAYMENT);
    }
}