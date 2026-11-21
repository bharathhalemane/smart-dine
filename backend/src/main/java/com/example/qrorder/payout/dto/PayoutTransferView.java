package com.example.qrorder.payout.dto;

import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import java.time.Instant;

public record PayoutTransferView(String id, String hotelId, String paymentId, long amountMinor, String currency,
                                 TransferStatus status, String note, boolean reversalDue, String providerTransferId,
                                 Instant createdAt, Instant updatedAt) {

    public static PayoutTransferView of(PayoutTransfer t) {
        return new PayoutTransferView(t.getId(), t.getHotelId(), t.getPaymentId(), t.getAmountMinor(), t.getCurrency(),
                t.getStatus(), t.getNote(), t.isReversalDue(), t.getProviderTransferId(), t.getCreatedAt(), t.getUpdatedAt());
    }
}