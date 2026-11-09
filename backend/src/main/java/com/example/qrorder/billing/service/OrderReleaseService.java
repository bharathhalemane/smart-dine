package com.example.qrorder.billing.service;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.ordering.service.OrderActor;
import com.example.qrorder.ordering.service.OrderTransitionService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * In a pay-before-food hotel, moves held orders to the kitchen once the table has paid for them. Orders are
 * released oldest first, and an order is released when what the table has paid covers the bill up to and
 * including that order (tax, service charge and discounts applied the same way as on the real bill).
 * Safe to call any number of times, from anywhere: each move is a compare-and-set.
 */
@Service
public class OrderReleaseService {

    private static final Logger log = LoggerFactory.getLogger(OrderReleaseService.class);

    private final DiningSessionRepository sessions;
    private final OrderRepository orders;
    private final OrderTransitionService transitions;
    private final BillService bills;
    private final PaymentSettingsService paymentSettings;

    public OrderReleaseService(DiningSessionRepository sessions, OrderRepository orders,
                               OrderTransitionService transitions, BillService bills,
                               PaymentSettingsService paymentSettings) {
        this.sessions = sessions;
        this.orders = orders;
        this.transitions = transitions;
        this.bills = bills;
        this.paymentSettings = paymentSettings;
    }

    /** For the cashier: look again at this table now. */
    public int releaseNow(TenantContext tenant, String sessionId) {
        tenant.require(Permission.TAKE_PAYMENT);
        DiningSession session = sessions.findByIdAndHotelId(sessionId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Session"));
        return release(session);
    }

    /** After a payment, a cancellation or a discount. Never lets a problem here undo what just happened. */
    public int releaseQuietly(DiningSession session) {
        try {
            return release(session);
        } catch (RuntimeException e) {
            log.warn("Could not release paid orders for session {}", session.getId(), e);
            return 0;
        }
    }

    public int releaseForSession(String hotelId, String sessionId) {
        return sessions.findByIdAndHotelId(sessionId, hotelId).map(this::releaseQuietly).orElse(0);
    }

    public int release(DiningSession session) {
        if (!session.isActive() || !paymentSettings.effective(session.getHotelId()).isPayBeforeFood()) {
            return 0;
        }
        List<Order> all = orders.findByDiningSessionIdOrderByCreatedAtAscIdAsc(session.getId());
        if (all.stream().noneMatch(o -> o.getStatus() == OrderStatus.AWAITING_PAYMENT)) {
            return 0;
        }

        Bill bill = bills.currentBill(session);
        long paid = bill.getPaidMinor();
        List<Order> counted = new ArrayList<>();
        for (Order order : all) {
            if (order.getStatus() != OrderStatus.AWAITING_PAYMENT && order.getStatus() != OrderStatus.CANCELLED) {
                counted.add(order);
            }
        }

        int released = 0;
        for (Order order : all) {
            if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
                continue;
            }
            List<Order> upToHere = new ArrayList<>(counted);
            upToHere.add(order);
            long subtotal = BillCalculator.compute(upToHere, true).subtotalMinor();
            long due = BillMath.compute(subtotal, bill.getDiscounts(), bill.getTaxRateBps(), bill.isTaxInclusive(),
                    bill.getServiceChargeBps()).totalMinor();
            if (paid < due) {
                break; // oldest first: nothing later can jump the queue
            }

            Optional<Order> moved = transitions.transition(
                    Criteria.where("id").is(order.getId()).and("hotelId").is(order.getHotelId()),
                    OrderStatus.AWAITING_PAYMENT, OrderStatus.PENDING, OrderActor.system("payment"), null);
            if (moved.isPresent()) {
                counted.add(moved.get());
                released++;
                continue;
            }
            // Someone else moved it first. If it was released (not cancelled) it still counts toward the total.
            Order fresh = orders.findById(order.getId()).orElse(null);
            if (fresh != null && fresh.getStatus() != OrderStatus.AWAITING_PAYMENT
                    && fresh.getStatus() != OrderStatus.CANCELLED) {
                counted.add(fresh);
            }
        }
        return released;
    }
}