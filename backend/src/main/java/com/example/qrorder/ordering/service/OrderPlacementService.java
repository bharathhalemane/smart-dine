package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.ordering.dto.CartLine;
import com.example.qrorder.ordering.dto.PricedCart;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.repository.OrderRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OrderPlacementService {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    /** created is false when this call returned an order that an earlier identical request had made. */
    public record Placement(Order order, boolean created) {}

    private final OrderRepository orders;
    private final OrderPricingService pricing;
    private final Clock clock;

    public OrderPlacementService(OrderRepository orders, OrderPricingService pricing, Clock clock) {
        this.orders = orders;
        this.pricing = pricing;
        this.clock = clock;
    }

    public Placement place(CustomerContext customer, String idempotencyKey, List<CartLine> cart) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Send an Idempotency-Key header of 16 to 64 characters: letters, digits, _ or -");
        }
        String requestHash = OrderRequestHash.of(cart);

        // A retry of an order that already exists returns that order, even if the table has since moved on.
        Optional<Order> existing = orders.findByCustomerSessionIdAndIdempotencyKey(
                customer.customerSessionId(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        // Read-then-insert: Step 7.5 makes this check atomic with the insert.
        if (customer.sessionStatus() != SessionStatus.OPEN) {
            throw DomainException.conflict("Ordering is paused for this table. Please ask your server");
        }

        PricedCart priced = pricing.price(customer, cart);
        Order order = new Order(customer.hotelId(), customer.diningSessionId(), customer.tableId(),
                customer.tableNumber(), customer.customerSessionId(), customer.label(), priced.currency(),
                priced.lines(), priced.subtotalMinor(), idempotencyKey, requestHash, clock.instant());
        try {
            return new Placement(orders.save(order), true);
        } catch (DuplicateKeyException race) {
            // A concurrent request with the same key won the insert. Return its order.
            Order winner = orders.findByCustomerSessionIdAndIdempotencyKey(customer.customerSessionId(),
                    idempotencyKey).orElseThrow(() -> race);
            return replay(winner, requestHash);
        }
    }

    private static Placement replay(Order existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw DomainException.conflict("This Idempotency-Key was already used for a different order");
        }
        return new Placement(existing, false);
    }
}