package com.example.qrorder.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.tenancy.model.Permission;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderTransitionPolicyTest {

    @Test
    void theKitchenMovesOrdersThroughPreparation() {
        assertThat(OrderTransitionPolicy.staffPermissions(OrderStatus.PENDING, OrderStatus.ACCEPTED))
                .containsExactly(Permission.KITCHEN_OPERATE);
        assertThat(OrderTransitionPolicy.staffPermissions(OrderStatus.ACCEPTED, OrderStatus.PREPARING))
                .containsExactly(Permission.KITCHEN_OPERATE);
        assertThat(OrderTransitionPolicy.staffPermissions(OrderStatus.PREPARING, OrderStatus.READY))
                .containsExactly(Permission.KITCHEN_OPERATE);
    }

    @Test
    void deliveryBelongsToWaitersNotTheKitchen() {
        Set<Permission> needed = OrderTransitionPolicy.staffPermissions(OrderStatus.READY, OrderStatus.DELIVERED);
        assertThat(needed).containsExactly(Permission.DELIVER_ORDERS);
        // a kitchen-only staff member (KITCHEN_OPERATE + TOGGLE_AVAILABILITY) holds none of them
        assertThat(needed).doesNotContain(Permission.KITCHEN_OPERATE, Permission.TOGGLE_AVAILABILITY);
    }

    @Test
    void eitherKitchenOrWaiterMayCancelAnOrderStillInProgress() {
        for (OrderStatus from : new OrderStatus[] {OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING}) {
            assertThat(OrderTransitionPolicy.staffPermissions(from, OrderStatus.CANCELLED))
                    .containsExactlyInAnyOrder(Permission.KITCHEN_OPERATE, Permission.DELIVER_ORDERS);
        }
    }

    @Test
    void illegalMovesHaveNoPermissionAtAll() {
        assertThatThrownBy(() -> OrderTransitionPolicy.staffPermissions(OrderStatus.READY, OrderStatus.CANCELLED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderTransitionPolicy.staffPermissions(OrderStatus.DELIVERED, OrderStatus.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderTransitionPolicy.staffPermissions(OrderStatus.PENDING, OrderStatus.READY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCustomerMayCancelOnlyBeforeTheKitchenAccepts() {
        assertThat(OrderTransitionPolicy.customerMayCancel(OrderStatus.PENDING)).isTrue();
        for (OrderStatus other : new OrderStatus[] {OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY,
                OrderStatus.DELIVERED, OrderStatus.CANCELLED}) {
            assertThat(OrderTransitionPolicy.customerMayCancel(other)).as(other.name()).isFalse();
        }
    }
}