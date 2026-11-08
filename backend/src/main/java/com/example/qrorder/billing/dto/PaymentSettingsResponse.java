package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import java.time.Instant;
import java.util.List;

public record PaymentSettingsResponse(List<PaymentMethod> enabledMethods, String upiId, String payeeName,
                                      boolean partialPaymentsAllowed, Instant updatedAt) {

    public static PaymentSettingsResponse from(PaymentSettings s) {
        return new PaymentSettingsResponse(s.getEnabledMethods(), s.getUpiId(), s.getPayeeName(),
                s.isPartialPaymentsAllowed(), s.getUpdatedAt());
    }
}