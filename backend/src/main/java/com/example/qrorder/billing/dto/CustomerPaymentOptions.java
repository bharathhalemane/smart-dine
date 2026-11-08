package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.PaymentMethod;
import java.util.List;

/**
 * What a diner can pay with. upiId, payeeName and upiLink are null unless UPI is enabled
 * (upiLink also needs an INR bill). billComplete is false while orders are still in progress.
 */
public record CustomerPaymentOptions(List<PaymentMethod> methods, String upiId, String payeeName, String upiLink,
                                     boolean partialPaymentsAllowed, String currency, long amountDueMinor,
                                     boolean billComplete) {
}