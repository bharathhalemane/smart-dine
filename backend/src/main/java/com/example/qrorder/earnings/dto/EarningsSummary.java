package com.example.qrorder.earnings.dto;

import java.time.LocalDate;
import java.util.List;

public record EarningsSummary(String hotelId, String currency, String timezone, LocalDate from, LocalDate to,
                             Totals totals, long balanceMinor, long paymentsWithoutActualFee, List<Day> days) {

    public record Totals(long payments, long collectedMinor, long refundedMinor, long feeMinor, long commissionMinor,
                         long payoutsMinor, long netEarnedMinor){

    }

    public record Day(LocalDate date, long collectedMinor, long refundedMinor, long feeMinor, long commissionMinor,
                      long payoutsMinor, long netMinor){}

}
    
    