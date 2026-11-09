package com.example.qrorder.billing.dto;

import java.time.Instant;
import java.util.List;

/**
 * differenceMinor = what the bill says was paid minus what the ledger says (positive: the bill claims too much).
 * owedMinor / overpaidMinor on a gap: what a cleared table still owes, or was overpaid.
 * truncated: more bills matched than the 5000 checked, so narrow days.
 */
public record ReconciliationReport(Instant generatedAt, Instant since, int days, int billsChecked, boolean truncated,
                                   List<BillDrift> billDrifts, List<SettlementGap> settlementGaps,
                                   List<OnlineMismatch> onlineMismatches, long staleOnlineAttempts,
                                   long needsReviewCount) {

    public record BillDrift(String billId, String sessionId, String tableNumber, long paidOnBillMinor,
                            long ledgerMinor, long differenceMinor) {}

    public record SettlementGap(String billId, String sessionId, String tableNumber, long totalMinor,
                                long paidMinor, long owedMinor, long overpaidMinor) {}

    public record OnlineMismatch(String kind, String attemptId, String sessionId, long amountMinor,
                                 String providerPaymentId) {}
}