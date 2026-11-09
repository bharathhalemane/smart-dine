package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** reference: a UPI transaction id or card approval code. payerLabel: who paid, e.g. "Guest 1", for split bills. */
public record RecordPaymentRequest(@NotNull PaymentMethod method, @Positive long amountMinor,
                                   @Size(max = 64) String reference, @Size(max = 200) String note,
                                   @Size(max = 30) String payerLabel) {
}