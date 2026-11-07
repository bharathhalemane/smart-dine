package com.example.qrorder.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;

class OrderTransitionIT extends AbstractIntegrationTest {

    private static final String HOTEL = "hotel-1";
    private static final String CUSTOMER = "cust-1";

    @Autowired OrderRepository orders;
    @Autowired OrderTransitionService engine;
    @Autowired CustomerOrderService customerOrders;

    @BeforeEach
    void clean() {
        orders.deleteAll();
    }

    private String newOrder() {
        return orders.save(new Order(HOTEL, "ds-1", "t-1", "T1", CUSTOMER, "Guest 1", "INR", List.of(), 0L,
                Instant.now())).getId();
    }

    /** Staff scope: this hotel + this order. A fresh Criteria every call. */
    private static Criteria staffScope(String orderId) {
        return Criteria.where("id").is(orderId).and("hotelId").is(HOTEL);
    }

    private Order stored(String orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    @Test
    void anOrderMovesThroughTheWholeLifecycleAndEveryStepIsRecorded() {
        String id = newOrder();
        OrderStatus[] path = {OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY,
                OrderStatus.DELIVERED};

        for (int i = 0; i < path.length - 1; i++) {
            Optional<Order> moved = engine.transition(staffScope(id), path[i], path[i + 1],
                    OrderActor.staff("user-" + i), null);
            assertThat(moved).as("%s -> %s", path[i], path[i + 1]).isPresent();
            assertThat(moved.get().getStatus()).isEqualTo(path[i + 1]);
        }

        Order order = stored(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        List<OrderStatusChange> history = order.getStatusHistory();
        assertThat(history).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(history.get(i).from()).isEqualTo(path[i]);
            assertThat(history.get(i).to()).isEqualTo(path[i + 1]);
            assertThat(history.get(i).actorType()).isEqualTo("STAFF");
            assertThat(history.get(i).actorId()).isEqualTo("user-" + i);
            assertThat(history.get(i).at()).isNotNull();
        }
        assertThat(order.getCancelledAt()).isNull();
    }

    @Test
    void aMoveFromTheWrongCurrentStateChangesNothing() {
        String id = newOrder();
        engine.transition(staffScope(id), OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderActor.staff("u1"), null);

        Optional<Order> stale = engine.transition(staffScope(id), OrderStatus.PENDING, OrderStatus.CANCELLED,
                OrderActor.staff("u2"), "too late");

        assertThat(stale).isEmpty();
        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(stored(id).getStatusHistory()).hasSize(1);
        assertThat(stored(id).getCancelledAt()).isNull();
    }

    @Test
    void theScopeDecidesWhoCanTouchAnOrder() {
        String id = newOrder();

        Criteria otherHotel = Criteria.where("id").is(id).and("hotelId").is("hotel-2");
        Criteria otherCustomer = Criteria.where("id").is(id).and("customerSessionId").is("someone-else");
        assertThat(engine.transition(otherHotel, OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderActor.staff("u"), null))
                .isEmpty();
        assertThat(engine.transition(otherCustomer, OrderStatus.PENDING, OrderStatus.CANCELLED,
                OrderActor.customer("someone-else"), null)).isEmpty();

        Criteria owner = Criteria.where("id").is(id).and("customerSessionId").is(CUSTOMER);
        assertThat(engine.transition(owner, OrderStatus.PENDING, OrderStatus.CANCELLED,
                OrderActor.customer(CUSTOMER), null)).isPresent();
        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void movesOutsideTheStateMachineAreRefusedBeforeTouchingTheDatabase() {
        String id = newOrder();

        assertThatThrownBy(() -> engine.transition(staffScope(id), OrderStatus.PENDING, OrderStatus.READY,
                OrderActor.staff("u"), null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.transition(staffScope(id), OrderStatus.DELIVERED, OrderStatus.PENDING,
                OrderActor.staff("u"), null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.transition(staffScope(id), OrderStatus.READY, OrderStatus.CANCELLED,
                OrderActor.staff("u"), null)).isInstanceOf(IllegalStateException.class);

        assertThat(stored(id).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored(id).getStatusHistory()).isEmpty();
    }

    @Test
    void aStaffCancellationRecordsWhoWhenAndWhy() {
        String id = newOrder();
        engine.transition(staffScope(id), OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderActor.staff("cook"), null);
        engine.transition(staffScope(id), OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderActor.staff("cook"), null);

        Optional<Order> cancelled = engine.transition(staffScope(id), OrderStatus.PREPARING, OrderStatus.CANCELLED,
                OrderActor.staff("manager-1"), "Out of paneer");

        assertThat(cancelled).isPresent();
        Order order = stored(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo("STAFF");
        assertThat(order.getCancelledAt()).isNotNull();
        OrderStatusChange last = order.getStatusHistory().get(2);
        assertThat(last.to()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(last.actorId()).isEqualTo("manager-1");
        assertThat(last.reason()).isEqualTo("Out of paneer");
    }

    @Test
    void whenManyActorsRaceForOnePendingOrderExactlyOneWins() throws Exception {
        for (int round = 0; round < 10; round++) {
            String id = newOrder();

            List<Callable<Optional<Order>>> tasks = new ArrayList<>();
            for (int cook = 0; cook < 3; cook++) {
                String who = "cook-" + cook;
                tasks.add(() -> engine.transition(staffScope(id), OrderStatus.PENDING, OrderStatus.ACCEPTED,
                        OrderActor.staff(who), null));
            }
            tasks.add(() -> engine.transition(
                    Criteria.where("id").is(id).and("customerSessionId").is(CUSTOMER),
                    OrderStatus.PENDING, OrderStatus.CANCELLED, OrderActor.customer(CUSTOMER), null));

            List<Optional<Order>> results = runTogether(tasks);

            assertThat(results.stream().filter(Optional::isPresent).count())
                    .as("round %d: exactly one winner", round).isEqualTo(1);
            Order order = stored(id);
            assertThat(order.getStatusHistory()).hasSize(1);
            assertThat(order.getStatusHistory().get(0).to()).isEqualTo(order.getStatus());
            assertThat(order.getStatus()).isIn(OrderStatus.ACCEPTED, OrderStatus.CANCELLED);
        }
    }

    @Test
    void aCustomerCancellationNowLeavesAHistoryEntry() {
        String id = newOrder();
        CustomerContext customer = new CustomerContext(CUSTOMER, HOTEL, "t-1", "T1", "ds-1", SessionStatus.OPEN, 1, null);

        customerOrders.cancel(customer, id);
        customerOrders.cancel(customer, id); // a repeat must not add a second entry

        Order order = stored(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo("CUSTOMER");
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).actorType()).isEqualTo("CUSTOMER");
        assertThat(order.getStatusHistory().get(0).actorId()).isEqualTo(CUSTOMER);
        assertThat(order.getStatusHistory().get(0).from()).isEqualTo(OrderStatus.PENDING);
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}