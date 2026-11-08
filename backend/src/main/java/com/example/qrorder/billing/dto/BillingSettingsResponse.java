package com.example.qrorder.billing.dto;

import com.example.qrorder.billing.model.BillingSettings;
import java.time.Instant;

public record BillingSettingsResponse(String taxName, int taxRateBps, boolean taxInclusive, int serviceChargeBps,
                                      Instant updatedAt) {

    public static BillingSettingsResponse from(BillingSettings s) {
        return new BillingSettingsResponse(s.getTaxName(), s.getTaxRateBps(), s.isTaxInclusive(),
                s.getServiceChargeBps(), s.getUpdatedAt());
    }
}