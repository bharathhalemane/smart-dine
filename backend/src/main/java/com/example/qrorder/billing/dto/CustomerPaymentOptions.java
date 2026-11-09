package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentTiming;
import java.util.List;

/**
 * What a diner can pay with. upiId, payeeName and upiLink are null unless UPI is enabled
 * (upiLink also needs an INR bill). billComplete is false while orders are still in progress.
 * paymentTiming says whether this hotel asks guests to pay before the food is made or after the meal.
 */
public record CustomerPaymentOptions(List<PaymentMethod> methods, String upiId, String payeeName, String upiLink,
                                     boolean partialPaymentsAllowed, String currency, long amountDueMinor,
                                     boolean billComplete, PaymentTiming paymentTiming) {
}