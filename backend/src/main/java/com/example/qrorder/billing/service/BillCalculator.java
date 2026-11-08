package com.example.qrorder.billing.service;

import com.example.qrorder.billing.model.BillLine;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderOption;
import java.util.ArrayList;
import java.util.List;

/** Pure rules for what goes on a bill. No database, no clock. */
final class BillCalculator {

    record Computed(List<BillLine> lines, long subtotalMinor, int ordersBilled, int ordersInProgress) {}

    private BillCalculator() {}

    /** Delivered orders are billed, cancelled ones are ignored, anything else is still in progress. */
    static Computed compute(List<Order> orders) {
        List<BillLine> lines = new ArrayList<>();
        long subtotal = 0;
        int billed = 0;
        int inProgress = 0;

        for (Order order : orders) {
            switch (order.getStatus()) {
                case DELIVERED -> {
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
                case CANCELLED -> { }
                default -> inProgress++;
            }
        }
        return new Computed(lines, subtotal, billed, inProgress);
    }
}