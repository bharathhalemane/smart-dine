package com.example.qrorder.billing.service;

import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.service.OrderAdmissionPolicy;
import org.springframework.stereotype.Component;

/** In a pay-before-food hotel a new order is held until it is paid for. */
@Component
public class PaymentTimingAdmission implements OrderAdmissionPolicy {

    private final PaymentSettingsService settings;

    public PaymentTimingAdmission(PaymentSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public OrderStatus initialStatus(String hotelId) {
        return settings.effective(hotelId).isPayBeforeFood() ? OrderStatus.AWAITING_PAYMENT : OrderStatus.PENDING;
    }
}