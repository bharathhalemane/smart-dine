package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.ordering.dto.CartLine;
import com.example.qrorder.ordering.dto.PricedCart;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OrderPlacementService {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final int MAX_ATTEMPTS = 10;
    private static final int WRITE_CONFLICT = 112;
    private static final int NO_SUCH_TRANSACTION  = 251;

    /** created is false when this call returned an order that an earlier identical request had made. */
    public record Placement(Order order, boolean created) {}

    private final OrderRepository orders;
    private final OrderPricingService pricing;
    private final OrderWriter writer;
    private final ObjectProvider<OrderAdmissionPolicy> admission;
    private final Clock clock;

    public OrderPlacementService(OrderRepository orders, OrderPricingService pricing,OrderWriter writer,
                                 ObjectProvider<OrderAdmissionPolicy> admission,Clock clock) {
        this.orders = orders;
        this.pricing = pricing;
        this.writer = writer;
        this.admission = admission;
        this.clock = clock;
    }

    public Placement place(CustomerContext customer, String idempotencyKey, List<CartLine> cart) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Send an Idempotency-Key header of 16 to 64 characters: letters, digits, _ or -");
        }
        String requestHash = OrderRequestHash.of(cart);

        for (int attempt = 1; ;attempt++) {
            try {
                return placeOnce(customer, idempotencyKey, cart, requestHash);
            } catch (RuntimeException e) {
                if (!isTransientConflict(e)) {
                    throw e;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw DomainException.conflict("The table is busy, please try again");
                }
                pause(attempt);
            }
        }
    }
    private Placement placeOnce(CustomerContext customer, String idempotencyKey, List<CartLine> cart, String requestHash){
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
        if (admission.orderedStream()
                .anyMatch(policy -> policy.initialStatus(customer.hotelId()) == OrderStatus.AWAITING_PAYMENT)) {
            order.holdForPayment();
        }
        try {
            return new Placement(writer.insertIntoOpenSession(order), true);
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

    static boolean isTransientConflict(Throwable error){
        for(Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()){
            if(t instanceof MongoException mongo && (mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) || mongo.getCode() == WRITE_CONFLICT || mongo.getCode() == NO_SUCH_TRANSACTION)) return true;
        }
        return false;
    }

    private static void pause(int attempt){
        try{
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25) * attempt);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            throw DomainException.conflict("Interrupted, please try again");
        }
    }
}