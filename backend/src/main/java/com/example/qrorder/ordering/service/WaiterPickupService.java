package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.ordering.dto.WaiterOrderActionResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.tables.model.WaiterEventType;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.StaffDirectory;
import com.example.qrorder.tables.service.WaiterAuditService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** A waiter takes a ready order, puts it back, or delivers it. Pickup never changes the order's status. */
@Service
public class WaiterPickupService {

    private final MongoTemplate mongo;
    private final OrderRepository orders;
    private final OrderTransitionService transitions;
    private final StaffDirectory directory;
    private final AccountService accounts;
    private final WaiterAuditService audit;
    private final Clock clock;

    public WaiterPickupService(MongoTemplate mongo, OrderRepository orders, OrderTransitionService transitions,
                               StaffDirectory directory, AccountService accounts, WaiterAuditService audit, Clock clock) {
        this.mongo = mongo;
        this.orders = orders;
        this.transitions = transitions;
        this.directory = directory;
        this.accounts = accounts;
        this.audit=audit;
        this.clock = clock;
    }

    /** One atomic claim: the order must be READY and held by nobody (or by the given stale holder). */
    public WaiterOrderActionResponse pickup(TenantContext tenant, String orderId) {
        tenant.require(Permission.DELIVER_ORDERS);
        String me = tenant.userId();

        Order claimed = tryClaim(tenant, orderId, null);
        if (claimed != null) {
            recordPickup(tenant, claimed, null);
            return view(claimed);
        }

        Order current = load(tenant, orderId);
        if (current.getStatus() != OrderStatus.READY) {
            throw notPickable(current);
        }
        String holder = current.getPickedUpByUserId();
        if (me.equals(holder)) {
            return view(current); // already yours
        }
        if (holder != null && !activeWaiters(tenant).contains(holder)) {
            Order takenOver = tryClaim(tenant, orderId, holder); // the holder no longer works as a waiter here
            if (takenOver != null) {
                recordPickup(tenant, takenOver, holder);
                return view(takenOver);
            }
            throw DomainException.conflict("This order was just picked up, please try again");
        }
        if (holder != null) {
            throw DomainException.conflict("This order is already being carried by " + nameOf(holder));
        }
        throw DomainException.conflict("This order was just changed, please try again");
    }

    /** Puts an order back. Your own pickup, or any pickup if you are a manager. A free order is a no-op. */
    public void drop(TenantContext tenant, String orderId) {
        tenant.require(Permission.DELIVER_ORDERS);
        boolean manager = tenant.can(Permission.MANAGE_TABLES);

        Criteria scope = Criteria.where("id").is(orderId).and("hotelId").is(tenant.hotelId())
                .and("status").is(OrderStatus.READY);
        if (manager) {
            scope = scope.and("pickedUpByUserId").ne(null);
        } else {
            scope = scope.and("pickedUpByUserId").is(tenant.userId());
        }
        Order before = mongo.findAndModify(Query.query(scope),
                new Update().unset("pickedUpByUserId").unset("pickedUpAt").inc("version", 1),
                FindAndModifyOptions.options().returnNew(false), Order.class);
        if (before != null) {
            audit.record(tenant.hotelId(), WaiterEventType.DROPPED, before.getTableId(), before.getTableNumber(),
                    before.getId(), tenant.userId(), before.getPickedUpByUserId(), null, null);
            return;
        }

        Order current = load(tenant, orderId);
        String holder = current.getPickedUpByUserId();
        if (current.getStatus() == OrderStatus.READY && holder != null && !holder.equals(tenant.userId())
                && !manager) {
            throw DomainException.conflict("This order is being carried by " + nameOf(holder));
        }
        // free, already delivered or cancelled: nothing to put back
    }

    /**
     * READY → DELIVERED. Allowed when nobody holds the order, when I hold it, when I am a manager, or when the
     * holder no longer works as a waiter here. One atomic transition, so simultaneous deliveries are one move.
     */
    public WaiterOrderActionResponse deliver(TenantContext tenant, String orderId) {
        OrderTransitionPolicy.requireStaff(tenant, OrderStatus.READY, OrderStatus.DELIVERED);
        Order before = load(tenant, orderId);

        Criteria scope = Criteria.where("id").is(orderId).and("hotelId").is(tenant.hotelId());
        if (!tenant.can(Permission.MANAGE_TABLES)) {
            List<String> allowed = new ArrayList<>();
            allowed.add(tenant.userId());
            String holder = before.getPickedUpByUserId();
            if (holder != null && !holder.equals(tenant.userId()) && !activeWaiters(tenant).contains(holder)) {
                allowed.add(holder);
            }
            scope = scope.orOperator(Criteria.where("pickedUpByUserId").is(null),
                    Criteria.where("pickedUpByUserId").in(allowed));
        }

        Optional<Order> moved = transitions.transition(scope, OrderStatus.READY, OrderStatus.DELIVERED,
                OrderActor.staff(tenant.userId()), null);
        if (moved.isPresent()) {
            return view(moved.get());
        }

        Order current = load(tenant, orderId);
        if (current.getStatus() == OrderStatus.DELIVERED) {
            return view(current); // someone else (or a double-tap) already did it
        }
        if (current.getStatus() == OrderStatus.CANCELLED) {
            throw DomainException.invalidTransition("This order has been cancelled");
        }
        if (current.getStatus() != OrderStatus.READY) {
            throw DomainException.invalidTransition("This order is " + current.getStatus().name().toLowerCase()
                    + ", so it cannot be delivered yet");
        }
        if (current.getPickedUpByUserId() != null) {
            throw DomainException.conflict("This order is being carried by " + nameOf(current.getPickedUpByUserId()));
        }
        throw DomainException.conflict("This order was just changed, please try again");
    }

    // ---------- helpers ----------
    private void recordPickup(TenantContext tenant, Order order, String previousHolder) {
        audit.record(tenant.hotelId(), WaiterEventType.PICKED_UP, order.getTableId(), order.getTableNumber(),
                order.getId(), tenant.userId(), previousHolder, tenant.userId(), null);
    }

    private Order tryClaim(TenantContext tenant, String orderId, String expectedHolder) {
        Criteria scope = Criteria.where("id").is(orderId).and("hotelId").is(tenant.hotelId())
                .and("status").is(OrderStatus.READY).and("pickedUpByUserId").is(expectedHolder);
        return mongo.findAndModify(Query.query(scope),
                new Update().set("pickedUpByUserId", tenant.userId()).set("pickedUpAt", clock.instant())
                        .inc("version", 1),
                FindAndModifyOptions.options().returnNew(true), Order.class);
    }

    private Order load(TenantContext tenant, String orderId) {
        return orders.findByIdAndHotelId(orderId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Order"));
    }

    private Set<String> activeWaiters(TenantContext tenant) {
        return directory.userIdsWith(tenant.hotelId(), Permission.DELIVER_ORDERS);
    }

    private static DomainException notPickable(Order current) {
        return switch (current.getStatus()) {
            case CANCELLED -> DomainException.invalidTransition("This order has been cancelled");
            case DELIVERED -> DomainException.invalidTransition("This order has already been delivered");
            default -> DomainException.invalidTransition("This order is not ready yet");
        };
    }

    private String nameOf(String userId) {
        AccountSummary summary = accounts.summaries(Set.of(userId)).get(userId);
        return summary == null ? "another waiter" : summary.name();
    }

    private WaiterOrderActionResponse view(Order order) {
        String carrier = order.getPickedUpByUserId();
        String name = null;
        if (carrier != null) {
            AccountSummary summary = accounts.summaries(Set.of(carrier)).get(carrier);
            name = summary == null ? null : summary.name();
        }
        return new WaiterOrderActionResponse(order.getId(), order.getStatus(), order.getTableNumber(),
                order.getCustomerLabel(), carrier, name, order.getPickedUpAt());
    }
}