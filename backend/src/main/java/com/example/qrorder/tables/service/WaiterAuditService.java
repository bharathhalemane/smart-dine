package com.example.qrorder.tables.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.tables.dto.WaiterEventResponse;
import com.example.qrorder.tables.model.WaiterEvent;
import com.example.qrorder.tables.model.WaiterEventType;
import com.example.qrorder.tables.repository.WaiterEventRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tables.event.FloorChanged;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The floor audit log. Writing it never breaks the action it describes. */
@Service
public class WaiterAuditService {

    private static final Logger log = LoggerFactory.getLogger(WaiterAuditService.class);

    private final WaiterEventRepository events;
    private final AccountService accounts;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public WaiterAuditService(WaiterEventRepository events, AccountService accounts, Clock clock,
                              ApplicationEventPublisher publisher) {
        this.events = events;
        this.accounts = accounts;
        this.publisher = publisher;
        this.clock = clock;
    }

    public void record(String hotelId, WaiterEventType type, String tableId, String tableNumber, String orderId,
                       String actorUserId, String fromUserId, String toUserId, String note) {
        try {
            events.save(new WaiterEvent(hotelId, type, tableId, tableNumber, orderId, actorUserId, fromUserId,
                    toUserId, note, clock.instant()));
            publisher.publishEvent(new FloorChanged(hotelId, type.name(), tableId, tableNumber, orderId));
        } catch (RuntimeException e) {
            log.error("Could not write waiter audit event {} for hotel {}", type, hotelId, e);
        }
    }

    /** Newest first. tableId may be null for the whole hotel. */
    public List<WaiterEventResponse> history(TenantContext tenant, String tableId, int limit) {
        tenant.require(Permission.MANAGE_TABLES);
        if (limit < 1 || limit > 100) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 100");
        }
        List<WaiterEvent> rows = (tableId == null || tableId.isBlank())
                ? events.findFirst100ByHotelIdOrderByAtDescIdDesc(tenant.hotelId())
                : events.findFirst100ByHotelIdAndTableIdOrderByAtDescIdDesc(tenant.hotelId(), tableId);
        List<WaiterEvent> page = rows.size() > limit ? rows.subList(0, limit) : rows;

        Set<String> ids = new HashSet<>();
        for (WaiterEvent e : page) {
            addIfPresent(ids, e.getActorUserId());
            addIfPresent(ids, e.getFromUserId());
            addIfPresent(ids, e.getToUserId());
        }
        Map<String, AccountSummary> people = accounts.summaries(ids);

        return page.stream().map(e -> new WaiterEventResponse(e.getId(), e.getType(), e.getTableId(),
                e.getTableNumber(), e.getOrderId(), e.getActorUserId(), name(people, e.getActorUserId()),
                e.getFromUserId(), name(people, e.getFromUserId()), e.getToUserId(),
                name(people, e.getToUserId()), e.getNote(), e.getAt())).toList();
    }

    private static void addIfPresent(Set<String> ids, String id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static String name(Map<String, AccountSummary> people, String id) {
        AccountSummary person = id == null ? null : people.get(id);
        return person == null ? null : person.name();
    }
}