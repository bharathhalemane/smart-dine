package com.example.qrorder.ordering.service;

import com.example.qrorder.dining.service.SessionOrderGate;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place an order is inserted. It is a separate bean so the transaction boundary is a real
 * proxy call, and so callers can catch a duplicate-key error AFTER the transaction has rolled back.
 */
@Service
public class OrderWriter {

    private final SessionOrderGate gate;
    private final OrderRepository orders;

    public OrderWriter(SessionOrderGate gate, OrderRepository orders) {
        this.gate = gate;
        this.orders = orders;
    }

    /** Gate and insert commit together or not at all. */
    @Transactional
    public Order insertIntoOpenSession(Order order) {
        gate.admitOrder(order.getHotelId(), order.getDiningSessionId());
        return orders.save(order);
    }
}