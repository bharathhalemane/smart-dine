package com.example.qrorder.ordering.dto;

import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
import java.time.Instant;
import java.util.List;

public record KitchenOrderResponse(String id, OrderStatus, status, String tableNumber, String customerLabel,
                                   List<OrderLine> lines, Instant createdAt, Instant statusSince){

    public static KitchenOrderResponse from(Order o){
        List<OrderStatusChange> history = 0.getStatusHistory();
        Instant since = history.isEmpty() ? o.getCreatedAt() : history.get(history.size() - 1).at();
        return new KitchenOrderResponse(o.getId(), o.getStatus(), o.getTableNumber(), o.getCustomerLabel(),
                o.getLines(), o.getCreatedAt(), since);
    }
}