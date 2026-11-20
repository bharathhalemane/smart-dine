package com.example.qrorder.earnings.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class EarningsMath {

    private EarningsMath(){}

    public static long bps(long amountMinor, int bps){
        if (amountMinor < 0 || bps < 0){
            throw new IllegalArgumentException("Amount and rate must not be negative");
        }
        return BigDecimal.valueOf(amountMinor).multiply(BigDecimal.valueOf(bps)).divide(BigDecimal.valueOf(10_000),0,RoundingMode.HALF_UP).longValueExact();
    }
    
}
    
    