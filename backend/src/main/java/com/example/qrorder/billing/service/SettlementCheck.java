package com.example.qrorder.billing.service;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.service.SessionCloseCheck;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Runs when a table is cleared. If the hotel requires settlement, the bill must have nothing owed and
 * nothing overpaid. Someone who can adjust bills may force it through; the clear is then audited with the amount.
 */
@Component
public class SettlementCheck implements SessionCloseCheck {

    private final SettlementSettingsService settings;
    private final BillService bills;

    public SettlementCheck(SettlementSettingsService settings, BillService bills) {
        this.settings = settings;
        this.bills = bills;
    }

    @Override
    public boolean guardsOrderCount(DiningSession session) {
        return settings.required(session.getHotelId());
    }

    @Override
    public void check(TenantContext tenant, DiningSession session) {
        check(tenant, session, false);
    }

    @Override
    public void check(TenantContext tenant, DiningSession session, boolean forced) {
        if (!settings.required(session.getHotelId())) {
            return;
        }
        Bill bill = bills.currentBill(session); // also refreshes the stored bill, which freezes right after this
        long owed = Math.max(0, bill.getTotalMinor() - bill.getPaidMinor());
        long overpaid = Math.max(0, bill.getPaidMinor() - bill.getTotalMinor());
        if (owed == 0 && overpaid == 0) {
            return;
        }
        if (!forced) {
            throw DomainException.conflict(owed > 0
                    ? "This table still has " + money(owed, bill) + " unpaid"
                    : "This table has been overpaid by " + money(overpaid, bill) + ", settle the refund first");
        }
        if (!tenant.can(Permission.ADJUST_BILL)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Only someone who can adjust bills may clear a table whose bill is not settled");
        }
    }

    @Override
    public Optional<String> warning(DiningSession session) {
        if (!settings.required(session.getHotelId())) {
            return Optional.empty();
        }
        Bill bill = bills.currentBill(session);
        long owed = Math.max(0, bill.getTotalMinor() - bill.getPaidMinor());
        long overpaid = Math.max(0, bill.getPaidMinor() - bill.getTotalMinor());
        if (owed > 0) {
            return Optional.of("Cleared with " + money(owed, bill) + " unpaid");
        }
        if (overpaid > 0) {
            return Optional.of("Cleared with " + money(overpaid, bill) + " overpaid");
        }
        return Optional.empty();
    }

    private static String money(long minor, Bill bill) {
        return UpiLinks.rupees(minor) + " " + bill.getCurrency();
    }
}