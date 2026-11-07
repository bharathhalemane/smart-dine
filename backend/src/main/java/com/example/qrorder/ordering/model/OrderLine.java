package com.example.qrorder.ordering.model;

import com.example.qrorder.menu.model.Diet;
import java.util.List;
import org.springframework.data.annotation.PersistenceCreator;


/** One priced line. Everything is a snapshot: later menu edits never change an existing order. */
public record OrderLine(String itemId, String itemName, Diet diet, String variantId, String variantName,
                        List<OrderOption> options, long unitPriceMinor, int quantity, long lineTotalMinor,
                        String note,LineStatus status, String statusNote) {

    @PersistenceCreator
    public OrderLine {
        if (status == null) {
            status = LineStatus.ACTIVE;
        }
    }

    public OrderLine(String itemId, String itemName, Diet diet, String variantId, String variantName,
                     List<OrderOption> options, long unitPriceMinor, int quantity, long lineTotalMinor,
                     String note) {
        this(itemId, itemName, diet, variantId, variantName, options, unitPriceMinor, quantity, lineTotalMinor,
                note, LineStatus.ACTIVE, null);
    }

    public boolean stillOrdered() {
        return status == LineStatus.ACTIVE;
    }

    public OrderLine markUnavailable(String reason) {
        return new OrderLine(itemId, itemName, diet, variantId, variantName, options, unitPriceMinor, quantity,
                lineTotalMinor, note, LineStatus.UNAVAILABLE, reason);
    }
}