package com.example.qrorder.ordering.service;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.ordering.dto.CartLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fingerprint of what the customer asked for: items, choices, quantities and notes, in order.
 * Add-on order and note whitespace do not matter; prices are deliberately not part of it,
 * so a retry after a menu price change still counts as the same request.
 */
public final class OrderRequestHash {

    private OrderRequestHash() {}

    public static String of(List<CartLine> cart) {
        StringBuilder canonical = new StringBuilder();
        for (CartLine line : cart) {
            List<String> options = line.optionIds() == null ? new ArrayList<>() : new ArrayList<>(line.optionIds());
            Collections.sort(options);
            part(canonical, line.itemId());
            part(canonical, line.variantId() == null ? "" : line.variantId());
            part(canonical, String.valueOf(options.size()));
            options.forEach(o -> part(canonical, o));
            part(canonical, String.valueOf(line.quantity()));
            part(canonical, line.note() == null ? "" : line.note().strip().replaceAll("\\s+", " "));
            canonical.append('|');
        }
        return Tokens.sha256(canonical.toString());
    }

    /** Length-prefixed, so no value can be crafted to blur the boundary between two fields. */
    private static void part(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value).append(';');
    }
}