package com.example.qrorder.ordering.model;

import static com.example.qrorder.ordering.model.OrderStatus.ACCEPTED;
import static com.example.qrorder.ordering.model.OrderStatus.AWAITING_PAYMENT;
import static com.example.qrorder.ordering.model.OrderStatus.CANCELLED;
import static com.example.qrorder.ordering.model.OrderStatus.DELIVERED;
import static com.example.qrorder.ordering.model.OrderStatus.PENDING;
import static com.example.qrorder.ordering.model.OrderStatus.PREPARING;
import static com.example.qrorder.ordering.model.OrderStatus.READY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderStatusTest {

    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
            AWAITING_PAYMENT, Set.of(PENDING, CANCELLED),
            PENDING, Set.of(ACCEPTED, CANCELLED),
            ACCEPTED, Set.of(PREPARING, CANCELLED),
            PREPARING, Set.of(READY, CANCELLED),
            READY, Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of());

    @Test
    void exactlyTheDocumentedMovesAreLegal() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void onlyDeliveredAndCancelledAreTerminal() {
        assertThat(DELIVERED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        for (OrderStatus status : EnumSet.of(AWAITING_PAYMENT, PENDING, ACCEPTED, PREPARING, READY)) {
            assertThat(status.isTerminal()).as(status.name()).isFalse();
        }
    }

    @Test
    void noOrderGetsStuckAndNothingGoesBackToWhereItWas() {
        for (OrderStatus start : OrderStatus.values()) {
            Set<OrderStatus> seen = EnumSet.noneOf(OrderStatus.class);
            Deque<OrderStatus> queue = new ArrayDeque<>(start.next());
            while (!queue.isEmpty()) {
                OrderStatus current = queue.poll();
                if (seen.add(current)) {
                    queue.addAll(current.next());
                }
            }
            assertThat(seen).as("reachable from %s", start).doesNotContain(AWAITING_PAYMENT);
            if (start != AWAITING_PAYMENT) {
                assertThat(seen).as("reachable from %s", start).doesNotContain(PENDING);
            }
            if (!start.isTerminal()) {
                assertThat(seen).as("%s must be able to finish", start).containsAnyOf(DELIVERED, CANCELLED);
            }
        }
    }
}