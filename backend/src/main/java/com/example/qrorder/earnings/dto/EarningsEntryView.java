package com.example.qrorder.earnings.dto;

import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.FeeSource;
import java.time.Instant;

public record EarningsEntryView(String id, EarningsEntryType type, String paymentId, String providerPaymentId,
                                String payoutID, String billId, String diningSessionId, String currency,
                                long grossMinor, long feeMinor, long commissionMinor, long netMinor,
                                FeeSource feeSource, Integer commissionBps, String reason, Instant occurredAt) {

    public static EarningsEntryView from(EarningsEntry e){
        return new EarningsEntryView(e.getId(), e.getType(), e.getPaymentId(), e.getProviderPaymentId(),e.getPayoutId(),
                e.getBillId(), e.getDiningSessionId(), e.getCurrency(), e.getGrossMinor(), e.getFeeMinor(), e.getCommissionMinor(),
                e.getNetMinor(), e.getFeeSource(), e.getCommissionBps(), e.getReason(), e.getOccurredAt());
    }

}
    
    