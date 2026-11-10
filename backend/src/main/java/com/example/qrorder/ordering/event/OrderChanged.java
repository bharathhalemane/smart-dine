package com.example.qrorder.ordering.event;

import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;

/** An order was placed or moved to a new status. Published only once the change is committed. */
public record OrderChanged(String hotelId, String diningSessionId, String orderId, String tableNumber,
                           String customerLabel, OrderStatus from, OrderStatus to, Long version, boolean placed) {

    public static OrderChanged placed(Order order) {
        return new OrderChanged(order.getHotelId(), order.getDiningSessionId(), order.getId(),
                order.getTableNumber(), order.getCustomerLabel(), null, order.getStatus(), order.getVersion(), true);
    }

    public static OrderChanged moved(Order order, OrderStatus from, OrderStatus to) {
        return new OrderChanged(order.getHotelId(), order.getDiningSessionId(), order.getId(),
                order.getTableNumber(), order.getCustomerLabel(), from, to, order.getVersion(), false);
    }
}