package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.dto.SessionDetailResponse;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.dining.service.SessionLifecycleService;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.ordering.dto.ClearableTableResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tables.dto.TableResponse;
import com.example.qrorder.tables.model.WaiterEventType;
import com.example.qrorder.tables.service.TableService;
import com.example.qrorder.tables.service.WaiterAssignmentService;
import com.example.qrorder.tables.service.WaiterAuditService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class WaiterClearService {

    /** Orders someone is still working on. Delivered and cancelled orders do not hold a table. */
    private static final Set<OrderStatus> IN_PROGRESS = EnumSet.of(OrderStatus.PENDING, OrderStatus.ACCEPTED,
            OrderStatus.PREPARING, OrderStatus.READY);

    private final DiningSessionRepository sessions;
    private final OrderRepository orders;
    private final SessionLifecycleService lifecycle;
    private final WaiterAssignmentService assignments;
    private final WaiterAuditService audit;
    private final TableService tables;
    private final AccountService accounts;

    public WaiterClearService(DiningSessionRepository sessions, OrderRepository orders,
                              SessionLifecycleService lifecycle, WaiterAssignmentService assignments,
                              WaiterAuditService audit, TableService tables, AccountService accounts) {
        this.sessions = sessions;
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.assignments = assignments;
        this.audit = audit;
        this.tables = tables;
        this.accounts = accounts;
    }

    /** Tables with a live session in natural order. scope "mine": my tables plus unclaimed ones; "all": every one. */
    public List<ClearableTableResponse> list(TenantContext tenant, String scope) {
        tenant.require(Permission.CLEAR_TABLE);
        boolean everything;
        if ("all".equals(scope)) {
            everything = true;
        } else if ("mine".equals(scope)) {
            everything = false;
        } else {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "scope must be 'mine' or 'all'");
        }

        Map<String, DiningSession> sessionByTable = sessions.findByHotelIdAndActiveTrue(tenant.hotelId()).stream()
                .collect(Collectors.toMap(DiningSession::getTableId, Function.identity(), (a, b) -> a));
        Map<String, Long> inProgress = orders.findByHotelIdAndStatusIn(tenant.hotelId(), IN_PROGRESS).stream()
                .collect(Collectors.groupingBy(Order::getDiningSessionId, Collectors.counting()));
        Map<String, String> waiterByTable = assignments.responsibleWaiters(tenant.hotelId());

        List<TableResponse> visibleTables = new ArrayList<>();
        Set<String> waiterIds = new HashSet<>();
        for (TableResponse table : tables.list(tenant, false)) {
            if (!sessionByTable.containsKey(table.id())) {
                continue;
            }
            String waiter = waiterByTable.get(table.id());
            if (!everything && waiter != null && !waiter.equals(tenant.userId())) {
                continue;
            }
            visibleTables.add(table);
            if (waiter != null) {
                waiterIds.add(waiter);
            }
        }
        Map<String, AccountSummary> people = accounts.summaries(waiterIds);

        List<ClearableTableResponse> rows = new ArrayList<>();
        for (TableResponse table : visibleTables) {
            DiningSession session = sessionByTable.get(table.id());
            int open = inProgress.getOrDefault(session.getId(), 0L).intValue();
            String waiter = waiterByTable.get(table.id());
            AccountSummary person = waiter == null ? null : people.get(waiter);
            rows.add(new ClearableTableResponse(table.id(), table.number(), table.section(), session.getId(),
                    session.getStatus(), session.getOpenedAt(), open, open == 0,
                    open == 0 ? null : describe(open) + " in progress", waiter,
                    person == null ? null : person.name(), tenant.userId().equals(waiter), waiter == null));
        }
        return rows;
    }

    /** Clears the table by closing its live session. Orders in progress block it unless a manager forces it. */
    public SessionDetailResponse clear(TenantContext tenant, String tableId, boolean force) {
        tenant.require(Permission.CLEAR_TABLE);
        TableResponse table = assignments.activeTable(tenant, tableId);
        DiningSession session = sessions.findByHotelIdAndActiveTrue(tenant.hotelId()).stream()
                .filter(s -> s.getTableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> DomainException.invalidTransition("This table is already free"));

        String note = null;
        long open = orders.countByDiningSessionIdAndStatusIn(session.getId(), IN_PROGRESS);
        if (open > 0) {
            if (!force) {
                throw DomainException.conflict("This table still has " + describe(open) + " in progress");
            }
            if (!tenant.can(Permission.MANAGE_TABLES)) {
                throw new DomainException(ErrorCode.FORBIDDEN,
                        "Only a manager can clear a table with orders in progress");
            }
            note = "Cleared with " + describe(open) + " in progress";
        }

        SessionDetailResponse closed = lifecycle.close(tenant, session.getId());
        audit.record(tenant.hotelId(), WaiterEventType.TABLE_CLEARED, tableId, table.number(), null,
                tenant.userId(), null, null, note);
        return closed;
    }

    private static String describe(long count) {
        return count + (count == 1 ? " order" : " orders");
    }
}