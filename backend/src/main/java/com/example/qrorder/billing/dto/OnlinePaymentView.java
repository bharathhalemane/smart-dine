package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import java.time.Instant;

public record OnlinePaymentView(String id, String sessionId, String tableNumber, long amountMinor, String currency,
                                OnlinePaymentStatus status, String providerPaymentId, String reviewReason,
                                String resolutionNote, Instant createdAt, Instant updatedAt) {

    public static OnlinePaymentView from(OnlinePayment p) {
        return new OnlinePaymentView(p.getId(), p.getDiningSessionId(), p.getTableNumber(), p.getAmountMinor(),
                p.getCurrency(), p.getStatus(), p.getProviderPaymentId(), p.getReviewReason(),
                p.getResolutionNote(), p.getCreatedAt(), p.getUpdatedAt());
    }
}