package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.ordering.dto.StaffOrderDetailResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLineReport;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

@Service
public class StaffOrderService {

    private static final int MAX_ATTEMPTS = 3;

    private final OrderRepository orders;
    private final OrderTransitionService transitions;
    private final ObjectProvider<OrderCancelListener> cancelListeners;
    private final AccountService accounts;

    public StaffOrderService(OrderRepository orders, OrderTransitionService transitions, AccountService accounts,
                             ObjectProvider<OrderCancelListener> cancelListeners) {
        this.orders = orders;
        this.transitions = transitions;
        this.accounts = accounts;
        this.cancelListeners = cancelListeners;
    }

    public StaffOrderDetailResponse detail(TenantContext tenant, String orderId) {
        if (Stream.of(Permission.KITCHEN_OPERATE, Permission.DELIVER_ORDERS).noneMatch(tenant::can)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You are not allowed to do that");
        }
        return toDetail(find(tenant, orderId));
    }

    /**
     * Staff cancellation. A reason is mandatory. The state is read, then the move is a compare-and-set
     * from exactly that state; if the order moved in between (a cook accepted it), read again and retry.
     */
    public StaffOrderDetailResponse cancel(TenantContext tenant, String orderId, String rawReason) {
        // The permission is the same from every state the order can be cancelled from.
        OrderTransitionPolicy.requireStaff(tenant, OrderStatus.PENDING, OrderStatus.CANCELLED);
        String reason = OrderNotes.clean(rawReason);
        if (reason == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "A reason is required to cancel an order");
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Order current = find(tenant, orderId);
            if (current.getStatus() == OrderStatus.CANCELLED) {
                return toDetail(current); // already cancelled: the first reason and history stand
            }
            if (!current.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
                throw DomainException.invalidTransition("This order is " + current.getStatus().name().toLowerCase()
                        + ", so it can no longer be cancelled");
            }
            Optional<Order> moved = transitions.transition(
                    Criteria.where("id").is(orderId).and("hotelId").is(tenant.hotelId()),
                    current.getStatus(), OrderStatus.CANCELLED, OrderActor.staff(tenant.userId()), reason);
            if (moved.isPresent()) {
                Order done = moved.get();
                cancelListeners.orderedStream()
                        .forEach(listener -> listener.afterCancelled(done.getHotelId(), done.getDiningSessionId()));
                return toDetail(done);
            }
        }
        throw DomainException.conflict("The order keeps changing, please try again");
    }

    private Order find(TenantContext tenant, String orderId) {
        return orders.findByIdAndHotelId(orderId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Order"));
    }

    private StaffOrderDetailResponse toDetail(Order order) {
        Set<String> staffIds = new HashSet<>();
        for (OrderStatusChange change : order.getStatusHistory()) {
            if (OrderActor.STAFF.equals(change.actorType())) {
                staffIds.add(change.actorId());
            }
        }
        for (OrderLineReport report : order.getLineReports()) {
            staffIds.add(report.reportedBy());
        }
        Map<String, AccountSummary> names = staffIds.isEmpty() ? Map.of() : accounts.summaries(staffIds);

        List<StaffOrderDetailResponse.Entry> history = order.getStatusHistory().stream()
                .map(change -> new StaffOrderDetailResponse.Entry(change.from(), change.to(), change.actorType(),
                        change.actorId(),
                        OrderActor.STAFF.equals(change.actorType()) ? nameOf(names, change.actorId())
                                : order.getCustomerLabel(),
                        change.reason(), change.at()))
                .toList();
        List<StaffOrderDetailResponse.LineReport> reports = order.getLineReports().stream()
                .map(r -> new StaffOrderDetailResponse.LineReport(r.lineNo(), r.itemName(), r.reason(),
                        r.reportedBy(), nameOf(names, r.reportedBy()), r.at()))
                .toList();

        return new StaffOrderDetailResponse(order.getId(), order.getStatus(), order.getTableNumber(),
                order.getCustomerLabel(), order.getCurrency(), order.getLines(), order.getSubtotalMinor(),
                order.getCreatedAt(), order.getCancelledAt(), order.getCancelledBy(), order.getCancelReason(),
                history, reports);
    }

    private static String nameOf(Map<String, AccountSummary> names, String userId) {
        AccountSummary summary = names.get(userId);
        return summary == null ? null : summary.name();
    }
}