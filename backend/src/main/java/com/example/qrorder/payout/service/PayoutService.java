package com.example.qrorder.payout.service;

import com.example.qrorder.billing.config.PlatformRazorpayProperties;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.payout.gateway.RouteGateway;
import com.example.qrorder.payout.model.HotelPayoutAccount;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Sends a hotel's share of each platform payment to its linked account. At most one transfer per payment (unique index),
 * and a transfer is claimed before it is sent, so two servers never send the same one.
 */
@Service
public class PayoutService {

    static final long RAZORPAY_MINIMUM_MINOR = 100; // 1.00: Razorpay will not transfer less
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final MongoTemplate mongo;
    private final EarningsEntryRepository entries;
    private final RouteGateway gateway;
    private final PlatformRazorpayProperties platform;
    private final Clock clock;

    public PayoutService(MongoTemplate mongo, EarningsEntryRepository entries, RouteGateway gateway,
                         PlatformRazorpayProperties platform, Clock clock) {
        this.mongo = mongo;
        this.entries = entries;
        this.gateway = gateway;
        this.platform = platform;
        this.clock = clock;
    }

    /**
     * Creates the transfer for a counted platform payment (once) and tries to send it. Safe to call again for the same
     * payment. Needs the payment's ledger entry, which the payment write already created.
     */
    public PayoutTransfer schedule(String hotelId, String paymentId, String providerPaymentId) {
        Optional<PayoutTransfer> existing = find(paymentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        EarningsEntry earned = entries.findByUniqueKey(EarningsEntry.keyOf(EarningsEntryType.ONLINE_PAYMENT, hotelId, paymentId))
                .orElseThrow(() -> new IllegalStateException("No earnings entry for payment " + paymentId));
        long amount = earned.getNetMinor();
        Instant now = clock.instant();

        PayoutTransfer transfer;
        if (amount < RAZORPAY_MINIMUM_MINOR) {
            transfer = new PayoutTransfer(hotelId, paymentId, providerPaymentId, Math.max(0, amount), earned.getCurrency(),
                    TransferStatus.SKIPPED, "The hotel's share is below Razorpay's minimum transfer", now);
        } else {
            transfer = new PayoutTransfer(hotelId, paymentId, providerPaymentId, amount, earned.getCurrency(),
                    TransferStatus.PENDING, null, now);
        }
        try {
            transfer = mongo.insert(transfer);
        } catch (DuplicateKeyException raced) {
            return find(paymentId).orElseThrow(() -> raced);
        }
        return transfer.getStatus() == TransferStatus.PENDING ? sendOne(transfer.getId()) : transfer;
    }

    /** Tries to send one PENDING transfer. Returns its state afterwards. Never throws for a Razorpay problem. */
    public PayoutTransfer sendOne(String transferId) {
        Instant now = clock.instant();
        // claim: PENDING (or an abandoned claim) to SENDING for two minutes. Only one caller wins.
        PayoutTransfer claimed = mongo.findAndModify(
                Query.query(Criteria.where("id").is(transferId).and("status").is(TransferStatus.PENDING)),
                new Update().set("status", TransferStatus.SENDING).set("lockedUntil", now.plus(LEASE))
                        .set("updatedAt", now).inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true), PayoutTransfer.class);
        if (claimed == null) {
            return mongo.findById(transferId, PayoutTransfer.class); // someone else has it, or it is not PENDING
        }

        HotelPayoutAccount account = mongo.findOne(Query.query(Criteria.where("hotelId").is(claimed.getHotelId())),
                HotelPayoutAccount.class);
        if (account == null) {
            return finish(claimed, TransferStatus.PENDING, null, "Waiting: this hotel has no payout account yet", null);
        }
        if (!platform.enabled()) {
            return finish(claimed, TransferStatus.PENDING, null, "Waiting: the platform Razorpay keys are not set", null);
        }

        try {
            RouteGateway.Result result = gateway.transfer(platform.credentials(), claimed.getProviderPaymentId(),
                    account.getLinkedAccountId(), claimed.getAmountMinor(), claimed.getCurrency(), claimed.getId());
            return finish(claimed, TransferStatus.SENT, result.transferId(), "Sent to Razorpay", account.getLinkedAccountId());
        } catch (RouteGateway.Rejected e) {
            log.warn("Transfer {} refused: {}", claimed.getId(), e.getMessage());
            return finish(claimed, TransferStatus.FAILED, null, e.getMessage(), account.getLinkedAccountId());
        } catch (RouteGateway.Unreachable e) {
            log.warn("Transfer {} not sent, Razorpay unreachable", claimed.getId());
            return finish(claimed, TransferStatus.PENDING, null, "Waiting: Razorpay could not be reached", null);
        } catch (RouteGateway.Uncertain e) {
            log.error("Transfer {} may or may not have reached Razorpay: {}", claimed.getId(), e.getMessage());
            return finish(claimed, TransferStatus.NEEDS_REVIEW, null,
                    "Check the Razorpay dashboard before sending again: " + e.getMessage(), account.getLinkedAccountId());
        }
    }

    /** Sends everything waiting. Returns how many were tried. A scheduler calls this in step 2b. */
    public int sendPending(int limit) {
        List<PayoutTransfer> waiting = mongo.find(Query.query(Criteria.where("status").is(TransferStatus.PENDING))
                .limit(limit), PayoutTransfer.class);
        waiting.forEach(t -> sendOne(t.getId()));
        return waiting.size();
    }

    private Optional<PayoutTransfer> find(String paymentId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("paymentId").is(paymentId)), PayoutTransfer.class));
    }

    private PayoutTransfer finish(PayoutTransfer claimed, TransferStatus status, String providerTransferId, String note,
                                  String linkedAccountId) {
        Update update = new Update().set("status", status).set("note", note).set("updatedAt", clock.instant())
                .unset("lockedUntil");
        if (providerTransferId != null) {
            update.set("providerTransferId", providerTransferId);
        }
        if (linkedAccountId != null) {
            update.set("linkedAccountId", linkedAccountId);
        }
        return mongo.findAndModify(Query.query(Criteria.where("id").is(claimed.getId())), update,
                FindAndModifyOptions.options().returnNew(true), PayoutTransfer.class);
    }
}