package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.BillResponse;
import com.example.qrorder.billing.dto.GuestTotal;
import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.BillLine;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.HotelLookupService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * One bill per dining session. While the session is live every read re-derives the bill from its orders and
 * stores it only when something changed. Once the session is closed the stored bill is frozen.
 */
@Service
public class BillService {

    private final BillRepository bills;
    private final DiningSessionRepository sessions;
    private final OrderRepository orders;
    private final HotelLookupService hotels;
    private final MongoTemplate mongo;
    private final Clock clock;

    public BillService(BillRepository bills, DiningSessionRepository sessions, OrderRepository orders,
                       HotelLookupService hotels, MongoTemplate mongo, Clock clock) {
        this.bills = bills;
        this.sessions = sessions;
        this.orders = orders;
        this.hotels = hotels;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Staff view. Cashiers, waiters, managers and owners. */
    public BillResponse forStaff(TenantContext tenant, String sessionId) {
        tenant.requireAnyPermission(Permission.TAKE_PAYMENT, Permission.DELIVER_ORDERS);
        DiningSession session = sessions.findByIdAndHotelId(sessionId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Session"));
        return view(session, current(session));
    }

    /** A diner sees the bill of their own table's session, nothing else. */
    public BillResponse forCustomer(CustomerContext customer) {
        DiningSession session = sessions.findByIdAndHotelId(customer.diningSessionId(), customer.hotelId())
                .orElseThrow(() -> DomainException.notFound("Session"));
        return view(session, current(session));
    }

    // ---------- internals ----------

    private Bill current(DiningSession session) {
        Bill existing = bills.findByDiningSessionId(session.getId()).orElse(null);
        if (!session.isActive() && existing != null) {
            return existing; // the table was cleared: the bill is frozen
        }
        BillCalculator.Computed computed = BillCalculator.compute(
                orders.findByDiningSessionIdOrderByCreatedAtAscIdAsc(session.getId()));
        if (existing != null && existing.sameContentAs(computed.lines(), computed.subtotalMinor(),
                computed.ordersBilled(), computed.ordersInProgress())) {
            return existing;
        }
        return store(session, computed);
    }

    /** Insert or update in one atomic step. The unique index on the session settles two first-time callers. */
    private Bill store(DiningSession session, BillCalculator.Computed computed) {
        String currency = hotels.findCurrency(session.getHotelId())
                .orElseThrow(() -> DomainException.notFound("Hotel"));
        for (int attempt = 0; ; attempt++) {
            try {
                Bill updated = mongo.findAndModify(
                        Query.query(Criteria.where("diningSessionId").is(session.getId())),
                        new Update()
                                .set("hotelId", session.getHotelId())
                                .set("tableId", session.getTableId())
                                .set("tableNumber", session.getTableNumber())
                                .set("currency", currency)
                                .set("lines", computed.lines())
                                .set("subtotalMinor", computed.subtotalMinor())
                                .set("totalMinor", computed.subtotalMinor()) // 10.2 adds tax, service charge, discounts
                                .set("ordersBilled", computed.ordersBilled())
                                .set("ordersInProgress", computed.ordersInProgress())
                                .set("updatedAt", clock.instant())
                                .setOnInsert("createdAt", clock.instant())
                                .inc("revision", 1),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), Bill.class);

                return updated;
            } catch (DuplicateKeyException firstInsertCollided) {
                if (attempt >= 1) {
                    throw firstInsertCollided; // the second try takes the update path, so this is not expected
                }
            }
        }
    }

    private static BillResponse view(DiningSession session, Bill bill) {
        Map<String, Long> perGuest = new LinkedHashMap<>();
        for (BillLine line : bill.getLines()) {
            perGuest.merge(line.customerLabel(), line.lineTotalMinor(), Long::sum);
        }
        List<GuestTotal> guests = new ArrayList<>();
        perGuest.forEach((label, total) -> guests.add(new GuestTotal(label, total)));

        return new BillResponse(bill.getId(), session.getId(), session.getStatus(), bill.getTableNumber(),
                bill.getCurrency(), bill.getLines(), guests, bill.getSubtotalMinor(), bill.getTotalMinor(),
                bill.getOrdersBilled(), bill.getOrdersInProgress(), bill.getOrdersInProgress() == 0,
                bill.getRevision(), bill.getUpdatedAt());
    }
}