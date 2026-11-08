package com.example.qrorder.billing.service;

import com.example.qrorder.billing.model.BillDiscount;
import com.example.qrorder.billing.model.DiscountType;
import java.util.ArrayList;
import java.util.List;

/**
 * The arithmetic of a bill. Integer paise only, rounded half up at each step. No database, no clock.
 *
 * subtotal - discounts = net; net + service charge = taxable;
 * tax is added on top (exclusive) or extracted from inside (inclusive).
 */
final class BillMath {

    record Breakdown(long discountMinor, long serviceChargeMinor, long taxMinor, long totalMinor,
                     List<Long> discountAmounts) {}

    private BillMath() {}

    static Breakdown compute(long subtotal, List<BillDiscount> discounts, int taxRateBps, boolean taxInclusive,
                             int serviceChargeBps) {
        long remaining = subtotal;
        long discountTotal = 0;
        List<Long> amounts = new ArrayList<>();
        for (BillDiscount discount : discounts) {
            long raw = discount.type() == DiscountType.PERCENT
                    ? percentOf(subtotal, discount.value()) : discount.value();
            long applied = Math.min(raw, remaining); // never more than what is left of the subtotal
            amounts.add(applied);
            remaining -= applied;
            discountTotal += applied;
        }

        long net = subtotal - discountTotal;
        long service = percentOf(net, serviceChargeBps);
        long taxable = net + service;

        long tax;
        long total;
        if (taxInclusive) {
            tax = divideRounded(taxable * taxRateBps, 10_000L + taxRateBps);
            total = taxable;
        } else {
            tax = percentOf(taxable, taxRateBps);
            total = taxable + tax;
        }
        return new Breakdown(discountTotal, service, tax, total, amounts);
    }

    static long percentOf(long amount, long basisPoints) {
        return divideRounded(amount * basisPoints, 10_000L);
    }

    private static long divideRounded(long numerator, long denominator) {
        return (numerator + denominator / 2) / denominator;
    }
}