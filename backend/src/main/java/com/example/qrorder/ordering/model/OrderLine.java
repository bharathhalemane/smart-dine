package com.example.qrorder.ordering.model;

import com.example.qrorder.menu.model.Diet;
import java.util.List;

/** One priced line. Everything is a snapshot: later menu edits never change an existing order. */
public record OrderLine(String itemId, String itemName, Diet diet, String variantId, String variantName,
                        List<OrderOption> options, long unitPriceMinor, int quantity, long lineTotalMinor,
                        String note) {
}