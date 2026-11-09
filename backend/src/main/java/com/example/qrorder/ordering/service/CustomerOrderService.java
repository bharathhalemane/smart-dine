package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.ordering.dto.OrderResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** What a diner may do with their own orders. Always scoped by the customer session from the cookie. */
@Service
public class CustomerOrderService {

    private final OrderRepository orders;
    private final OrderTransitionService transitions;
    private final ObjectProvider<OrderCancelListener> cancelListeners;

    public CustomerOrderService(OrderRepository orders,
                                ObjectProvider<OrderCancelListener> cancelListeners,OrderTransitionService transitions) {
        this.orders = orders;
        this.transitions = transitions;
        this.cancelListeners = cancelListeners;
    }

    public List<OrderResponse> list(CustomerContext customer) {
        return orders.findFirst50ByCustomerSessionIdOrderByCreatedAtDescIdDesc(customer.customerSessionId())
                .stream().map(OrderResponse::from).toList();
    }

    public OrderResponse get(CustomerContext customer, String orderId) {
        return OrderResponse.from(find(customer, orderId));
    }

    public OrderResponse cancel(CustomerContext customer, String orderId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Order current = find(customer, orderId); // 404 for anything that is not this customer's own order
            if (current.getStatus() == OrderStatus.CANCELLED) {
                return OrderResponse.from(current); // already cancelled: same answer
            }
            if (!OrderTransitionPolicy.customerMayCancel(current.getStatus())) {
                throw DomainException.conflict("This order can no longer be cancelled because it is "
                        + current.getStatus().name().toLowerCase());
            }
            Optional<Order> cancelled = transitions.transition(
                    Criteria.where("id").is(orderId).and("customerSessionId").is(customer.customerSessionId()),
                    current.getStatus(), OrderStatus.CANCELLED, OrderActor.customer(customer.customerSessionId()),
                    null);
            if (cancelled.isPresent()) {
                Order done = cancelled.get();
                cancelListeners.orderedStream()
                        .forEach(listener -> listener.afterCancelled(done.getHotelId(), done.getDiningSessionId()));
                return OrderResponse.from(done);
            }
            // it moved between our read and our update (a payment released it, or the kitchen accepted it): look again
        }
        throw DomainException.conflict("The order keeps changing, please try again");
    }

    private Order find(CustomerContext customer, String orderId) {
        return orders.findByIdAndCustomerSessionId(orderId, customer.customerSessionId())
                .orElseThrow(() -> DomainException.notFound("Order"));
    }
}