package com.example.qrorder.billing.service;

import com.example.qrorder.billing.model.BillLine;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderOption;
import com.example.qrorder.ordering.model.OrderStatus;
import java.util.ArrayList;
import java.util.List;

/** Pure rules for what goes on a bill. No database, no clock. */
final class BillCalculator {

    record Computed(List<BillLine> lines, long subtotalMinor, int ordersBilled, int ordersInProgress) {}

    private BillCalculator() {}

    /** Pay after the meal: delivered orders are billed, cancelled ones ignored, anything else is in progress. */
    static Computed compute(List<Order> orders) {
        return compute(orders, false);
    }

    /**
     * payFirst is for hotels where guests pay before the food is made: every order that is not cancelled is
     * billed, whatever stage it is at, because that is what the guest is asked to pay. Nothing is "in progress".
     */
    static Computed compute(List<Order> orders, boolean payFirst) {
        List<BillLine> lines = new ArrayList<>();
        long subtotal = 0;
        int billed = 0;
        int inProgress = 0;

        for (Order order : orders) {
            OrderStatus status = order.getStatus();
            if (status == OrderStatus.CANCELLED) {
                continue;
            }
            if (status != OrderStatus.DELIVERED && !payFirst) {
                inProgress++;
                continue;
            }
            billed++;
            for (OrderLine line : order.getLines()) {
                if (!line.stillOrdered()) {
                    continue; // the kitchen could not serve it
                }
                List<String> options = line.options() == null ? List.of()
                        : line.options().stream().map(OrderOption::optionName).toList();
                lines.add(new BillLine(order.getId(), order.getCustomerLabel(), line.itemName(),
                        line.variantName(), options, line.quantity(), line.unitPriceMinor(),
                        line.lineTotalMinor(), line.note()));
                subtotal += line.lineTotalMinor();
            }
        }
        return new Computed(lines, subtotal, billed, inProgress);
    }
}