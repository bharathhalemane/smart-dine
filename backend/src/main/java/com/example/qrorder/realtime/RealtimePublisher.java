package com.example.qrorder.realtime;

import com.example.qrorder.billing.event.BillChanged;
import com.example.qrorder.dining.event.SessionChanged;
import com.example.qrorder.ordering.event.OrderChanged;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.tables.event.FloorChanged;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns committed changes into messages on the channels from 11.2. Listens AFTER the database commit, so a
 * rolled-back change never reaches anyone; outside a transaction (a single atomic update) it sends at once.
 * A problem here is logged and never undoes or fails the change that caused it.
 */
@Component
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final SimpMessagingTemplate broker;
    private final Clock clock;

    public RealtimePublisher(SimpMessagingTemplate broker, Clock clock) {
        this.broker = broker;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrder(OrderChanged e) {
        RealtimeEvent event = RealtimeEvent.of(e, clock.instant());
        OrderStatus from = e.from();
        OrderStatus to = e.to();
        boolean held = to == OrderStatus.AWAITING_PAYMENT;
        boolean wasHeld = from == OrderStatus.AWAITING_PAYMENT;

        send(Topics.table(e.diningSessionId()), event);
        if (!held && !(wasHeld && to == OrderStatus.CANCELLED)) {
            send(Topics.kitchen(e.hotelId()), event); // the kitchen never sees an order still waiting for payment
        }
        if (!wasHeld && (to == OrderStatus.READY || to == OrderStatus.DELIVERED || to == OrderStatus.CANCELLED)) {
            send(Topics.waiters(e.hotelId()), event);
        }
        if (e.placed() || held || wasHeld || to == OrderStatus.DELIVERED || to == OrderStatus.CANCELLED) {
            send(Topics.billing(e.hotelId()), event);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBill(BillChanged e) {
        RealtimeEvent event = RealtimeEvent.of(e, clock.instant());
        send(Topics.table(e.diningSessionId()), event);
        send(Topics.billing(e.hotelId()), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSession(SessionChanged e) {
        RealtimeEvent event = RealtimeEvent.of(e, clock.instant());
        send(Topics.table(e.diningSessionId()), event);
        send(Topics.waiters(e.hotelId()), event);
        send(Topics.billing(e.hotelId()), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFloor(FloorChanged e) {
        send(Topics.waiters(e.hotelId()), RealtimeEvent.of(e, clock.instant()));
    }

    private void send(String destination, RealtimeEvent event) {
        try {
            broker.convertAndSend(destination, event);
        } catch (RuntimeException ex) {
            log.warn("Could not publish {} to {}", event.type(), destination, ex);
        }
    }
}