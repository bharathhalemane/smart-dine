package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentStatus;
import java.time.Instant;

public record PaymentResponse(String id, PaymentMethod method, long amountMinor, PaymentStatus status,
                              String reference, String note, String payerLabel, String receivedByUserId,
                              Instant receivedAt, String voidedByUserId, Instant voidedAt, String voidReason) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getMethod(), p.getAmountMinor(), p.getStatus(), p.getReference(),
                p.getNote(), p.getPayerLabel(), p.getReceivedByUserId(), p.getReceivedAt(), p.getVoidedByUserId(),
                p.getVoidedAt(), p.getVoidReason());
    }
}