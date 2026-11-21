package com.example.qrorder.earnings.service;

import com.example.qrorder.earnings.config.EarningsProperties;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.model.EarningsSettings;
import com.example.qrorder.earnings.model.FeeSource;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.earnings.repository.EarningsSettingsRepository;
import com.example.qrorder.tenancy.model.Hotel;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Writes the hotel earnings ledger. Safe to call twice for the same payment: the second call returns the first entry.
 * The methods use no transaction of their own, so they join the caller's one (the payment write) when there is one.
 */
@Service
public class EarningsLedger {

    private final EarningsEntryRepository entries;
    private final EarningsSettingsRepository settings;
    private final MongoTemplate mongo;
    private final EarningsProperties props;
    private final Clock clock;

    public EarningsLedger(EarningsEntryRepository entries, EarningsSettingsRepository settings, MongoTemplate mongo,
                          EarningsProperties props, Clock clock) {
        this.entries = entries;
        this.settings = settings;
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
    }

    /** The commission rate that applies to this hotel today, in basis points. */
    public int commissionBps(String hotelId) {
        return settings.findByHotelId(hotelId).map(EarningsSettings::getCommissionBps)
                .orElse(props.defaultCommissionBps());
    }

    /**
     * An online payment was received for this hotel.
     *
     * @param gatewayFeeMinor the total fee Razorpay reported (including GST on the fee), or null if it did not report one
     */
    public EarningsEntry recordOnlineCapture(String hotelId, String paymentId, String billId, String diningSessionId,
                                             String providerPaymentId, long grossMinor, Long gatewayFeeMinor,
                                             Instant occurredAt) {
        if (grossMinor <= 0) {
            throw new IllegalArgumentException("An online payment must be more than zero");
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (gatewayFeeMinor != null && gatewayFeeMinor < 0) {
            throw new IllegalArgumentException("The gateway fee cannot be negative");
        }

        Optional<EarningsEntry> existing = entries.findByUniqueKey(
                EarningsEntry.keyOf(EarningsEntryType.ONLINE_PAYMENT, hotelId, paymentId));
        if (existing.isPresent()) {
            return existing.get();
        }
        Hotel hotel = mongo.findById(hotelId, Hotel.class);
        if (hotel == null) {
            throw new IllegalArgumentException("Unknown hotel");
        }

        long fee;
        FeeSource source;
        if (gatewayFeeMinor != null) {
            fee = gatewayFeeMinor;
            source = FeeSource.WEBHOOK;
        } else if (props.fallbackFeeBps() > 0) {
            fee = EarningsMath.bps(grossMinor, props.fallbackFeeBps());
            source = FeeSource.ESTIMATE;
        } else {
            fee = 0;
            source = FeeSource.UNKNOWN;
        }

        int bps = commissionBps(hotelId);
        // commission can take at most what is left after the fee, so the hotel's share is never pushed below zero by it
        long commission = Math.max(0, Math.min(EarningsMath.bps(grossMinor, bps), grossMinor - fee));

        return entries.save(EarningsEntry.onlinePayment(hotelId, paymentId, billId, diningSessionId, providerPaymentId,
                hotel.getCurrency(), grossMinor, fee, commission, source, bps, occurredAt, clock.instant()));
    }

    /**
     * An online payment was refunded or voided. Empty if that payment has no ledger entry (it was not an online payment,
     * or it was made before the ledger existed). Safe to call twice.
     */
    public Optional<EarningsEntry> recordReversal(String hotelId, String paymentId, String reason, Instant occurredAt) {
        Optional<EarningsEntry> original = entries.findByUniqueKey(
                EarningsEntry.keyOf(EarningsEntryType.ONLINE_PAYMENT, hotelId, paymentId));
        if (original.isEmpty()) {
            return Optional.empty();
        }
        Optional<EarningsEntry> done = entries.findByUniqueKey(
                EarningsEntry.keyOf(EarningsEntryType.REFUND, hotelId, paymentId));
        if (done.isPresent()) {
            return done;
        }
        return Optional.of(entries.save(EarningsEntry.reversalOf(original.get(), reason, occurredAt, clock.instant())));
    }

    /** Money was sent to the hotel. Safe to call twice for the same payoutId (a repeated webhook). */
    public EarningsEntry recordPayout(String hotelId, String payoutId, long amountMinor, String reason, Instant occurredAt) {
        String key = EarningsEntry.keyOf(EarningsEntryType.PAYOUT, hotelId, payoutId);
        Optional<EarningsEntry> existing = entries.findByUniqueKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        Hotel hotel = mongo.findById(hotelId, Hotel.class);
        if (hotel == null) {
            throw new IllegalArgumentException("Unknown hotel");
        }
        try {
            return entries.save(EarningsEntry.payout(hotelId, payoutId, amountMinor, hotel.getCurrency(), reason,
                    occurredAt, clock.instant()));
        } catch (DuplicateKeyException twoWebhooksAtOnce) {
            return entries.findByUniqueKey(key).orElseThrow(() -> twoWebhooksAtOnce);
        }
    }
}