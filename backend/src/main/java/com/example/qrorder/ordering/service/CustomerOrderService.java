package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.ordering.dto.OrderResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** What a diner may do with their own orders. Always scoped by the customer session from the cookie. */
@Service
public class CustomerOrderService {

    private final OrderRepository orders;
    private final MongoTemplate mongo;
    private final Clock clock;

    public CustomerOrderService(OrderRepository orders, MongoTemplate mongo, Clock clock) {
        this.orders = orders;
        this.mongo = mongo;
        this.clock = clock;
    }

    public List<OrderResponse> list(CustomerContext customer) {
        return orders.findFirst50ByCustomerSessionIdOrderByCreatedAtDescIdDesc(customer.customerSessionId())
                .stream().map(OrderResponse::from).toList();
    }

    public OrderResponse get(CustomerContext customer, String orderId) {
        return OrderResponse.from(find(customer, orderId));
    }

    /**
     * PENDING to CANCELLED as a compare-and-set: it only happens if the order is still PENDING at the
     * instant of the write. When a customer cancels while the kitchen accepts (same filter in Phase 8),
     * exactly one of the two wins. Cancelling an already-cancelled order succeeds again, unchanged.
     */
    public OrderResponse cancel(CustomerContext customer, String orderId) {
        find(customer, orderId); // 404 for anything that is not this customer's own order

        Query query = Query.query(Criteria.where("id").is(orderId)
                .and("customerSessionId").is(customer.customerSessionId())
                .and("status").is(OrderStatus.PENDING));
        Update update = new Update()
                .set("status", OrderStatus.CANCELLED)
                .set("cancelledAt", clock.instant())
                .set("cancelledBy", "CUSTOMER")
                .inc("version", 1);
        Order cancelled = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
                Order.class);
        if (cancelled != null) {
            return OrderResponse.from(cancelled);
        }

        // Nothing matched: it was already cancelled (fine, same answer) or the kitchen got there first.
        Order current = find(customer, orderId);
        if (current.getStatus() == OrderStatus.CANCELLED) {
            return OrderResponse.from(current);
        }
        throw DomainException.conflict("This order can no longer be cancelled because it is "
                + current.getStatus().name().toLowerCase());
    }

    private Order find(CustomerContext customer, String orderId) {
        return orders.findByIdAndCustomerSessionId(orderId, customer.customerSessionId())
                .orElseThrow(() -> DomainException.notFound("Order"));
    }
}