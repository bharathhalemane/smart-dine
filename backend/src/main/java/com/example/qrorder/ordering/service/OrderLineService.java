package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.menu.service.MenuAvailabilityService;
import com.example.qrorder.ordering.dto.KitchenOrderResponse;
import com.example.qrorder.ordering.model.LineStatus;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderLineReport;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class OrderLineService {

    /** Items can only be taken off while the kitchen still has work to do on the order. */
    private static final Set<OrderStatus> EDITABLE =
            EnumSet.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING);
    private static final int MAX_ATTEMPTS = 5;

    private final OrderRepository orders;
    private final MongoTemplate mongo;
    private final MenuAvailabilityService menuAvailability;
    private final Clock clock;

    public OrderLineService(OrderRepository orders, MongoTemplate mongo, MenuAvailabilityService menuAvailability,
                            Clock clock) {
        this.orders = orders;
        this.mongo = mongo;
        this.menuAvailability = menuAvailability;
        this.clock = clock;
    }

    public KitchenOrderResponse reportUnavailable(TenantContext tenant, String orderId, String lineNoText,
                                                  String rawReason, Boolean alsoMarkMenuItem) {
        tenant.require(Permission.KITCHEN_OPERATE);
        boolean markMenuItem = alsoMarkMenuItem == null || alsoMarkMenuItem;
        if (markMenuItem) {
            tenant.require(Permission.TOGGLE_AVAILABILITY);
        }
        String reason = OrderNotes.clean(rawReason);
        int lineNo = parseLineNo(lineNoText);

        Order result = null;
        String itemId = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS && result == null; attempt++) {
            Order order = orders.findByIdAndHotelId(orderId, tenant.hotelId())
                    .orElseThrow(() -> DomainException.notFound("Order"));
            List<OrderLine> lines = order.getLines();
            if (lineNo >= lines.size()) {
                throw DomainException.notFound("Order line");
            }
            OrderLine line = lines.get(lineNo);
            itemId = line.itemId();

            if (line.status() == LineStatus.UNAVAILABLE) {
                result = order; // already reported: same answer, nothing to change
                break;
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
                throw DomainException.invalidTransition("This order has been cancelled");
            }
            if (!EDITABLE.contains(order.getStatus())) {
                throw DomainException.invalidTransition("This order is " + order.getStatus().name().toLowerCase()
                        + ", so its items can no longer be changed");
            }
            result = tryUpdate(tenant, order, lineNo, line, reason).orElse(null);
            // empty: the order changed under us (another report, or a status move). Read it again and retry.
        }
        if (result == null) {
            throw DomainException.conflict("The order keeps changing, please try again");
        }

        if (markMenuItem) {
            try {
                menuAvailability.setAvailable(tenant, itemId, false);
            } catch (DomainException itemGone) {
                // The dish was removed from the menu meanwhile, which already keeps it from being ordered.
            }
        }
        return KitchenOrderResponse.from(result);
    }

    /** Applies the change only if the order is still at the version we read: no lost updates. */
    private Optional<Order> tryUpdate(TenantContext tenant, Order order, int lineNo, OrderLine line, String reason) {
        Instant now = clock.instant();
        List<OrderLine> newLines = new ArrayList<>(order.getLines());
        newLines.set(lineNo, line.markUnavailable(reason));
        long subtotal = newLines.stream().filter(OrderLine::stillOrdered).mapToLong(OrderLine::lineTotalMinor).sum();
        boolean nothingLeft = newLines.stream().noneMatch(OrderLine::stillOrdered);

        Query query = Query.query(Criteria.where("id").is(order.getId())
                .and("hotelId").is(tenant.hotelId())
                .and("version").is(order.getVersion())
                .and("status").is(order.getStatus()));
        Update update = new Update()
                .set("lines", newLines)
                .set("subtotalMinor", subtotal)
                .push("lineReports", new OrderLineReport(lineNo, line.itemName(), reason, tenant.userId(), now))
                .inc("version", 1);
        if (nothingLeft) {
            update.set("status", OrderStatus.CANCELLED)
                    .set("cancelledAt", now)
                    .set("cancelledBy", OrderActor.STAFF)
                    .set("cancelReason", "All items unavailale")
                    .push("statusHistory", new OrderStatusChange(order.getStatus(), OrderStatus.CANCELLED,
                            OrderActor.STAFF, tenant.userId(), "All items unavailable", now));
        }
        return Optional.ofNullable(mongo.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Order.class));
    }

    private static int parseLineNo(String text) {
        try {
            int lineNo = Integer.parseInt(text);
            if (lineNo >= 0) {
                return lineNo;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the same 404 as any other bad line number
        }
        throw DomainException.notFound("Order line");
    }
}