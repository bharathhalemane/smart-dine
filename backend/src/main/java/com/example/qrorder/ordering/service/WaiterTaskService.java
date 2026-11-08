package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.ordering.dto.WaiterTaskResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tables.service.WaiterAssignmentService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class WaiterTaskService {

    private final OrderRepository orders;
    private final WaiterAssignmentService assignments;
    private final AccountService accounts;
    private final Clock clock;

    public WaiterTaskService(OrderRepository orders, WaiterAssignmentService assignments, AccountService accounts,
                             Clock clock) {
        this.orders = orders;
        this.assignments = assignments;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** scope "mine": my tables plus tables nobody is responsible for. scope "all": everything that is ready. */
    public List<WaiterTaskResponse> tasks(TenantContext tenant, String scope) {
        tenant.require(Permission.DELIVER_ORDERS);
        boolean everything;
        if ("all".equals(scope)) {
            everything = true;
        } else if ("mine".equals(scope)) {
            everything = false;
        } else {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "scope must be 'mine' or 'all'");
        }

        Instant now = clock.instant();
        Map<String, String> waiterByTable = assignments.responsibleWaiters(tenant.hotelId());
        List<Order> ready = orders.findFirst200ByHotelIdAndStatusInOrderByCreatedAtAscIdAsc(
                tenant.hotelId(), List.of(OrderStatus.READY));

        List<Order> visible = new ArrayList<>();
        for (Order order : ready) {
            String waiter = waiterByTable.get(order.getTableId());
            if (everything || waiter == null || waiter.equals(tenant.userId())) {
                visible.add(order);
            }
        }

        Set<String> waiterIds = new HashSet<>();
        for (Order order : visible) {
            String waiter = waiterByTable.get(order.getTableId());
            if (waiter != null) {
                waiterIds.add(waiter);
            }
        }
        Map<String, AccountSummary> people = accounts.summaries(waiterIds);

        List<WaiterTaskResponse> tasks = new ArrayList<>();
        for (Order order : visible) {
            Instant readyAt = readyAt(order);
            String waiter = waiterByTable.get(order.getTableId());
            AccountSummary person = waiter == null ? null : people.get(waiter);
            List<OrderLine> lines = order.getLines().stream().filter(OrderLine::stillOrdered).toList();
            tasks.add(new WaiterTaskResponse(order.getId(), order.getTableId(), order.getTableNumber(),
                    order.getCustomerLabel(), lines, readyAt,
                    Math.max(0, Duration.between(readyAt, now).getSeconds()),
                    waiter, person == null ? null : person.name(),
                    tenant.userId().equals(waiter), waiter == null));
        }
        tasks.sort(Comparator.comparing(WaiterTaskResponse::readyAt).thenComparing(WaiterTaskResponse::orderId));
        return tasks;
    }

    /** When the order most recently became READY; the creation time if its history says nothing. */
    private static Instant readyAt(Order order) {
        Instant found = null;
        if (order.getStatusHistory() != null) {
            for (OrderStatusChange change : order.getStatusHistory()) {
                if (change.to() == OrderStatus.READY && (found == null || change.at().isAfter(found))) {
                    found = change.at();
                }
            }
        }
        return found != null ? found : order.getCreatedAt();
    }
}