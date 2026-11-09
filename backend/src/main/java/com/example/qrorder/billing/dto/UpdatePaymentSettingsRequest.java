package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentTiming;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record UpdatePaymentSettingsRequest(@NotEmpty Set<PaymentMethod> enabledMethods,
                                           @Size(max = 100) String upiId,
                                           @Size(max = 60) String payeeName,
                                           @NotNull Boolean partialPaymentsAllowed,
                                           PaymentTiming paymentTiming) {
}