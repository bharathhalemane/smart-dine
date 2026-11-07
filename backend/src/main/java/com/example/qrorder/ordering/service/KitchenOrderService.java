package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.ordering.dto.KitchenOrderResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

@Service
public class KitchenOrderService {
    private static final Set<OrderStatus> OPEN = EnumSet.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY);

    private final OrderRepository orders;
    private final OrderTransitionService transitions;

    public KitchenOrderService(OrderRepository orders, OrderTransitionService transitions){
        this.orders = orders;
        this.transitions = transitions;
    }

    public List<KitchenOrderResponse> queue(TenantContext tenant, List<String> statusFilter) {
        if (Stream.of(Permission.KITCHEN_OPERATE, Permission.DELIVER_ORDERS).noneMatch(tenant::can)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You are not allowed to do that");
        }
        return orders.findFirst200ByHotelIdAndStatusInOrderByCreatedAtAscIdAsc(tenant.hotelId(), parse(statusFilter))
                .stream().map(KitchenOrderResponse::from).toList();
    }

    public KitchenOrderResponse accept(TenantContext tenant, String orderId) {
        return move(tenant, orderId, OrderStatus.PENDING, OrderStatus.ACCEPTED);
    }

    public KitchenOrderResponse start(TenantContext tenant, String orderId) {
        return move(tenant, orderId, OrderStatus.ACCEPTED, OrderStatus.PREPARING);
    }

    public KitchenOrderResponse ready(TenantContext tenant, String orderId) {
        return move(tenant, orderId, OrderStatus.PREPARING, OrderStatus.READY);
    }

    public KitchenOrderResponse deliver(TenantContext tenant, String orderId) {
        return move(tenant, orderId, OrderStatus.READY, OrderStatus.DELIVERED);
    }

    private KitchenOrderResponse move(TenantContext tenant, String orderId, OrderStatus expected, OrderStatus next) {
        OrderTransitionPolicy.requireStaff(tenant, expected, next);

        Optional<Order> moved = transitions.transition(
                Criteria.where("id").is(orderId).and("hotelId").is(tenant.hotelId()),
                expected, next, OrderActor.staff(tenant.userId()), null);
        if (moved.isPresent()) {
            return KitchenOrderResponse.from(moved.get());
        }

        Order current = orders.findByIdAndHotelId(orderId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Order"));
        if (current.getStatus() == next) {
            return KitchenOrderResponse.from(current); // someone else (or a double-tap) already did it
        }

        if (current.getStatus() == OrderStatus.CANCELLED) {
            throw DomainException.invalidTransition("This order has been cancelled");
        }

        throw DomainException.invalidTransition("This order is " + current.getStatus().name().toLowerCase()
                + ", so it cannot be moved to " + next.name().toLowerCase());
    }

    private static Set<OrderStatus> parse(List<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return OPEN;
        }
        Set<OrderStatus> result = EnumSet.noneOf(OrderStatus.class);
        for (String value : filter) {
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                OrderStatus status;
                try {
                    status = OrderStatus.valueOf(part.strip().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw invalid("Unknown status '" + part.strip() + "'");
                }
                if (!OPEN.contains(status)) {
                    throw invalid("The queue only lists PENDING, ACCEPTED, PREPARING and READY orders");
                }
                result.add(status);
            }
        }
        return result.isEmpty() ? OPEN : result;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}