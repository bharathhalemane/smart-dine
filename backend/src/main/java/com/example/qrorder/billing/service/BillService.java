package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.AddDiscountRequest;
import com.example.qrorder.billing.dto.BillResponse;
import com.example.qrorder.billing.dto.DiscountView;
import com.example.qrorder.billing.dto.GuestTotal;
import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.BillDiscount;
import com.example.qrorder.billing.model.BillLine;
import com.example.qrorder.billing.model.BillSnapshot;
import com.example.qrorder.billing.model.BillingSettings;
import com.example.qrorder.billing.model.DiscountType;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
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
import java.util.UUID;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * One bill per dining session. While the session is live every read re-derives the bill from its orders and
 * the hotel's current settings, and stores it only when something changed. Once the session is closed the
 * stored bill is frozen. Discounts are staff input and live on the bill itself.
 */
@Service
public class BillService {

    private static final int MAX_DISCOUNTS = 10;

    private final BillRepository bills;
    private final DiningSessionRepository sessions;
    private final OrderRepository orders;
    private final HotelLookupService hotels;
    private final BillingSettingsService settings;
    private final MongoTemplate mongo;
    private final Clock clock;

    public BillService(BillRepository bills, DiningSessionRepository sessions, OrderRepository orders,
                       HotelLookupService hotels, BillingSettingsService settings, MongoTemplate mongo,
                       Clock clock) {
        this.bills = bills;
        this.sessions = sessions;
        this.orders = orders;
        this.hotels = hotels;
        this.settings = settings;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Staff view. Cashiers, waiters, managers and owners. */
    public BillResponse forStaff(TenantContext tenant, String sessionId) {
        tenant.requireAnyPermission(Permission.TAKE_PAYMENT, Permission.DELIVER_ORDERS);
        DiningSession session = load(tenant, sessionId);
        return view(session, current(session), true);
    }

    /** A diner sees the bill of their own table's session, without staff details. */
    public BillResponse forCustomer(CustomerContext customer) {
        DiningSession session = sessions.findByIdAndHotelId(customer.diningSessionId(), customer.hotelId())
                .orElseThrow(() -> DomainException.notFound("Session"));
        return view(session, current(session), false);
    }

    // ---------- discounts ----------

    public BillResponse addDiscount(TenantContext tenant, String sessionId, AddDiscountRequest request) {
        tenant.require(Permission.ADJUST_BILL);
        DiningSession session = loadOpen(tenant, sessionId);
        Bill bill = current(session); // makes sure the bill exists and is fresh

        if (request.type() == DiscountType.PERCENT && request.value() > 10_000) {
            throw validation("A percentage discount cannot be more than 100%");
        }
        if (request.type() == DiscountType.FLAT && request.value() > bill.getSubtotalMinor()) {
            throw validation("The discount is larger than the bill");
        }

        // "discounts.9" not existing means there are at most 9 now, so after this push there are at most 10
        Bill updated = mongo.findAndModify(
                Query.query(Criteria.where("diningSessionId").is(sessionId).and("hotelId").is(tenant.hotelId())
                        .and("discounts." + (MAX_DISCOUNTS - 1)).exists(false)),
                new Update().push("discounts", new BillDiscount(UUID.randomUUID().toString(), request.type(),
                        request.value(), request.reason().strip(), tenant.userId(), clock.instant())),
                FindAndModifyOptions.options().returnNew(true), Bill.class);
        if (updated == null) {
            throw DomainException.conflict("A bill can carry at most " + MAX_DISCOUNTS + " discounts");
        }
        return view(session, current(session), true);
    }

    public BillResponse removeDiscount(TenantContext tenant, String sessionId, String discountId) {
        tenant.require(Permission.ADJUST_BILL);
        DiningSession session = loadOpen(tenant, sessionId);

        Bill updated = mongo.findAndModify(
                Query.query(Criteria.where("diningSessionId").is(sessionId).and("hotelId").is(tenant.hotelId())
                        .and("discounts.id").is(discountId)),
                new Update().pull("discounts", new Document("id", discountId)),
                FindAndModifyOptions.options().returnNew(true), Bill.class);
        if (updated == null) {
            throw DomainException.notFound("Discount");
        }
        return view(session, current(session), true);
    }

    // ---------- internals ----------

    private DiningSession load(TenantContext tenant, String sessionId) {
        return sessions.findByIdAndHotelId(sessionId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Session"));
    }

    /** A bill can only be adjusted while its table is still live. */
    private DiningSession loadOpen(TenantContext tenant, String sessionId) {
        DiningSession session = load(tenant, sessionId);
        if (!session.isActive()) {
            throw DomainException.invalidTransition("This table has been cleared, so its bill can no longer be changed");
        }
        return session;
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }

    private Bill current(DiningSession session) {
        Bill existing = bills.findByDiningSessionId(session.getId()).orElse(null);
        if (!session.isActive() && existing != null) {
            return existing; // the table was cleared: the bill is frozen
        }
        BillCalculator.Computed computed = BillCalculator.compute(
                orders.findByDiningSessionIdOrderByCreatedAtAscIdAsc(session.getId()));
        BillingSettings rates = settings.effective(session.getHotelId());
        BillMath.Breakdown breakdown = BillMath.compute(computed.subtotalMinor(),
                existing == null ? List.of() : existing.getDiscounts(), rates.getTaxRateBps(),
                rates.isTaxInclusive(), rates.getServiceChargeBps());

        BillSnapshot snapshot = new BillSnapshot(computed.lines(), computed.subtotalMinor(),
                breakdown.discountMinor(), breakdown.serviceChargeMinor(), breakdown.taxMinor(),
                breakdown.totalMinor(), computed.ordersBilled(), computed.ordersInProgress(), rates.getTaxName(),
                rates.getTaxRateBps(), rates.isTaxInclusive(), rates.getServiceChargeBps());
        if (existing != null && existing.matches(snapshot)) {
            return existing;
        }
        return store(session, snapshot);
    }

    /**
     * Insert or update in one atomic step. The unique index on the session settles two first-time callers.
     * Discounts are never touched here. If one is added while this runs, the next read re-totals it.
     */
    private Bill store(DiningSession session, BillSnapshot s) {
        String currency = hotels.findCurrency(session.getHotelId())
                .orElseThrow(() -> DomainException.notFound("Hotel"));
        for (int attempt = 0; ; attempt++) {
            try {
                return mongo.findAndModify(
                        Query.query(Criteria.where("diningSessionId").is(session.getId())),
                        new Update()
                                .set("hotelId", session.getHotelId())
                                .set("tableId", session.getTableId())
                                .set("tableNumber", session.getTableNumber())
                                .set("currency", currency)
                                .set("lines", s.lines())
                                .set("subtotalMinor", s.subtotalMinor())
                                .set("discountMinor", s.discountMinor())
                                .set("serviceChargeMinor", s.serviceChargeMinor())
                                .set("taxMinor", s.taxMinor())
                                .set("totalMinor", s.totalMinor())
                                .set("taxName", s.taxName())
                                .set("taxRateBps", s.taxRateBps())
                                .set("taxInclusive", s.taxInclusive())
                                .set("serviceChargeBps", s.serviceChargeBps())
                                .set("ordersBilled", s.ordersBilled())
                                .set("ordersInProgress", s.ordersInProgress())
                                .set("updatedAt", clock.instant())
                                .setOnInsert("createdAt", clock.instant())
                                .inc("revision", 1),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), Bill.class);
            } catch (DuplicateKeyException firstInsertCollided) {
                if (attempt >= 1) {
                    throw firstInsertCollided; // the second try takes the update path, so this is not expected
                }
            }
        }
    }

    private static BillResponse view(DiningSession session, Bill bill, boolean staff) {
        Map<String, Long> perGuest = new LinkedHashMap<>();
        for (BillLine line : bill.getLines()) {
            perGuest.merge(line.customerLabel(), line.lineTotalMinor(), Long::sum);
        }
        List<GuestTotal> guests = new ArrayList<>();
        perGuest.forEach((label, total) -> guests.add(new GuestTotal(label, total)));

        BillMath.Breakdown breakdown = BillMath.compute(bill.getSubtotalMinor(), bill.getDiscounts(),
                bill.getTaxRateBps(), bill.isTaxInclusive(), bill.getServiceChargeBps());
        List<DiscountView> discounts = new ArrayList<>();
        for (int i = 0; i < bill.getDiscounts().size(); i++) {
            BillDiscount d = bill.getDiscounts().get(i);
            long amount = breakdown.discountAmounts().get(i);
            discounts.add(staff
                    ? new DiscountView(d.id(), d.type(), d.value(), amount, d.reason(), d.byUserId(), d.at())
                    : new DiscountView(null, d.type(), d.value(), amount, null, null, null));
        }

        return new BillResponse(bill.getId(), session.getId(), session.getStatus(), bill.getTableNumber(),
                bill.getCurrency(), bill.getLines(), guests, bill.getSubtotalMinor(), bill.getDiscountMinor(),
                bill.getServiceChargeMinor(), bill.getTaxMinor(), bill.getTotalMinor(), bill.getTaxName(),
                bill.getTaxRateBps(), bill.isTaxInclusive(), bill.getServiceChargeBps(), discounts,
                bill.getOrdersBilled(), bill.getOrdersInProgress(), bill.getOrdersInProgress() == 0,
                bill.getRevision(), bill.getUpdatedAt());
    }
}