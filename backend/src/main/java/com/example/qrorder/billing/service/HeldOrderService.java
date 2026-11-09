package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.WaitingOrderResponse;
import com.example.qrorder.billing.model.HeldOrderSettings;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.ordering.service.OrderActor;
import com.example.qrorder.ordering.service.OrderTransitionService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Orders held for payment in a pay-before-food hotel: the cashier's list, and cancelling the ones nobody pays for.
 * An order is never expired while its table has paid something or an online payment is in flight, because
 * cancelling it would leave the bill overpaid.
 */
@Service
public class HeldOrderService {

    private static final Logger log = LoggerFactory.getLogger(HeldOrderService.class);
    private static final int BATCH = 200;
    private static final Duration ONLINE_ATTEMPT_WINDOW = Duration.ofMinutes(30);

    private final MongoTemplate mongo;
    private final OrderRepository orders;
    private final DiningSessionRepository sessions;
    private final OrderTransitionService transitions;
    private final BillService bills;
    private final HeldOrderSettingsService settings;
    private final Clock clock;

    public HeldOrderService(MongoTemplate mongo, OrderRepository orders, DiningSessionRepository sessions,
                            OrderTransitionService transitions, BillService bills,
                            HeldOrderSettingsService settings, Clock clock) {
        this.mongo = mongo;
        this.orders = orders;
        this.sessions = sessions;
        this.transitions = transitions;
        this.bills = bills;
        this.settings = settings;
        this.clock = clock;
    }

    // ---------- the cashier's list ----------

    public List<WaitingOrderResponse> waiting(TenantContext tenant) {
        tenant.require(Permission.TAKE_PAYMENT);
        Duration hold = Duration.ofMinutes(settings.minutes(tenant.hotelId()));
        Map<String, Long> paidBySession = new HashMap<>();
        List<WaitingOrderResponse> list = new ArrayList<>();
        for (Order order : orders.findFirst200ByHotelIdAndStatusInOrderByCreatedAtAscIdAsc(
                tenant.hotelId(), List.of(OrderStatus.AWAITING_PAYMENT))) {
            long paid = paidBySession.computeIfAbsent(order.getDiningSessionId(),
                    id -> paidSoFar(tenant.hotelId(), id));
            list.add(new WaitingOrderResponse(order.getId(), order.getDiningSessionId(), order.getTableNumber(),
                    order.getCustomerLabel(), order.getSubtotalMinor(), order.getCreatedAt(),
                    order.getCreatedAt().plus(hold), paid > 0));
        }
        return list;
    }

    // ---------- expiry ----------

    public int expireNow(TenantContext tenant) {
        tenant.requireAnyPermission(Permission.TAKE_PAYMENT, Permission.MANAGE_HOTEL);
        return expireDue(tenant.hotelId());
    }

    /** Cancels this hotel's held orders that have waited longer than its hold time. Returns how many. */
    public int expireDue(String hotelId) {
        Instant now = clock.instant();
        int minutes = settings.minutes(hotelId);
        Instant cutoff = now.minus(Duration.ofMinutes(minutes));
        List<Order> due = mongo.find(Query.query(Criteria.where("hotelId").is(hotelId)
                .and("status").is(OrderStatus.AWAITING_PAYMENT).and("createdAt").lt(cutoff))
                .with(Sort.by("createdAt", "id")).limit(BATCH), Order.class);

        Map<String, Boolean> moneyInPlay = new HashMap<>();
        int expired = 0;
        for (Order order : due) {
            try {
                boolean blocked = moneyInPlay.computeIfAbsent(order.getDiningSessionId(),
                        id -> hasMoneyInPlay(hotelId, id, now));
                if (blocked) {
                    continue;
                }
                boolean moved = transitions.transition(
                        Criteria.where("id").is(order.getId()).and("hotelId").is(hotelId),
                        OrderStatus.AWAITING_PAYMENT, OrderStatus.CANCELLED, OrderActor.system("expiry"),
                        "Not paid within " + minutes + " minutes").isPresent();
                if (moved) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire held order {}", order.getId(), e); // the next sweep tries again
            }
        }
        return expired;
    }

    /** Every hotel that has held orders old enough to matter. Called by the sweeper. */
    public int sweepAll() {
        Instant oldest = clock.instant().minus(Duration.ofMinutes(HeldOrderSettings.MIN_MINUTES));
        List<String> hotelIds = mongo.findDistinct(Query.query(Criteria.where("status")
                        .is(OrderStatus.AWAITING_PAYMENT).and("createdAt").lt(oldest)),
                "hotelId", Order.class, String.class);
        int total = 0;
        for (String hotelId : hotelIds) {
            try {
                total += expireDue(hotelId);
            } catch (RuntimeException e) {
                log.warn("Held-order sweep failed for hotel {}", hotelId, e);
            }
        }
        return total;
    }

    // ---------- helpers ----------

    private long paidSoFar(String hotelId, String sessionId) {
        return sessions.findByIdAndHotelId(sessionId, hotelId)
                .map(session -> bills.currentBill(session).getPaidMinor()).orElse(0L);
    }

    private boolean hasMoneyInPlay(String hotelId, String sessionId, Instant now) {
        if (paidSoFar(hotelId, sessionId) > 0) {
            return true;
        }
        return mongo.exists(Query.query(Criteria.where("diningSessionId").is(sessionId)
                .and("status").in(OnlinePaymentStatus.CREATED, OnlinePaymentStatus.NEEDS_REVIEW)
                .and("createdAt").gt(now.minus(ONLINE_ATTEMPT_WINDOW))), OnlinePayment.class);
    }
}