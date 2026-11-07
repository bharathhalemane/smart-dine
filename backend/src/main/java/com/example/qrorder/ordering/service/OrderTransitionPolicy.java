package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.EnumSet;
import java.util.Set;

public final class OrderTransitionPolicy {

    private OrderTransitionPolicy() {}

    public static Set<Permission> staffPermissions(OrderStatus from, OrderStatus to){
        if(!from.canTransitionTo(to)){
            throw new IllegalArgumentException("Transition " + from +" -> " + to +" is not in the state machine");
        }

        return switch (to) {
            case ACCEPTED, PREPARING, READY -> EnumSet.of(Permission.KITCHEN_OPERATE);
            case DELIVERED -> EnumSet.of(Permission.DELIVER_ORDERS);
            case CANCELLED -> EnumSet.of(Permission.KITCHEN_OPERATE, Permission.DELIVER_ORDERS);
            case PENDING -> throw new IllegalArgumentException("Nothing moves back to PENDING");
        };
    }

    public static void requireStaff(TenantContext tenant, OrderStatus from, OrderStatus to){
        if(staffPermissions(from,to).stream().noneMatch(tenant::can)){
            throw new DomainException(ErrorCode.FORBIDDEN, "You are not allowed to do that");
        }
    }

    public static boolean customerMayCancel(OrderStatus from){
        return from == OrderStatus.PENDING;
    }
}

