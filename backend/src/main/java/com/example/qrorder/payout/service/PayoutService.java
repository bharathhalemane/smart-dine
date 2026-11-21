package com.example.qrorder.payout.service;

import com.example.qrorder.billing.config.PlatformRazorpayProperties;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.earnings.service.EarningsLedger;
import com.example.qrorder.payout.gateway.RouteGateway;
import com.example.qrorder.payout.model.HotelPayoutAccount;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
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
    private final EarningsLedger ledger;
    private final RouteGateway gateway;
    private final PlatformRazorpayProperties platform;
    private final Clock clock;

    public PayoutService(MongoTemplate mongo, EarningsEntryRepository entries, EarningsLedger ledger,
                         RouteGateway gateway, PlatformRazorpayProperties platform, Clock clock) {
        this.mongo = mongo;
        this.entries = entries;
        this.ledger = ledger;
        this.gateway = gateway;
        this.platform = platform;
        this.clock = clock;
    }

    // ---------- create and send ----------

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
        // claim: PENDING to SENDING for two minutes. Only one caller wins.
        PayoutTransfer claimed = mongo.findAndModify(
                Query.query(Criteria.where("id").is(transferId).and("status").is(TransferStatus.PENDING)),
                new Update().set("status", TransferStatus.SENDING).set("lockedUntil", now.plus(LEASE))
                        .set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true), PayoutTransfer.class);
        if (claimed == null) {
            return mongo.findById(transferId, PayoutTransfer.class); // someone else has it, or it is not PENDING
        }

        HotelPayoutAccount account = mongo.findOne(Query.query(Criteria.where("hotelId").is(claimed.getHotelId())),
                HotelPayoutAccount.class);
        if (account == null) {
            return finish(claimed, TransferStatus.PENDING, null, "Waiting: this hotel has no payout account yet", null, false);
        }
        if (!platform.enabled()) {
            return finish(claimed, TransferStatus.PENDING, null, "Waiting: the platform Razorpay keys are not set", null, false);
        }

        try {
            RouteGateway.Result result = gateway.transfer(platform.credentials(), claimed.getProviderPaymentId(),
                    account.getLinkedAccountId(), claimed.getAmountMinor(), claimed.getCurrency(), claimed.getId());
            return finish(claimed, TransferStatus.SENT, result.transferId(), "Sent to Razorpay",
                    account.getLinkedAccountId(), true);
        } catch (RouteGateway.Rejected e) {
            log.warn("Transfer {} refused: {}", claimed.getId(), e.getMessage());
            return finish(claimed, TransferStatus.FAILED, null, e.getMessage(), account.getLinkedAccountId(), true);
        } catch (RouteGateway.Unreachable e) {
            log.warn("Transfer {} not sent, Razorpay unreachable", claimed.getId());
            return finish(claimed, TransferStatus.PENDING, null, "Waiting: Razorpay could not be reached", null, true);
        } catch (RouteGateway.Uncertain e) {
            log.error("Transfer {} may or may not have reached Razorpay: {}", claimed.getId(), e.getMessage());
            return finish(claimed, TransferStatus.NEEDS_REVIEW, null,
                    "Check the Razorpay dashboard before sending again: " + e.getMessage(),
                    account.getLinkedAccountId(), true);
        }
    }

    /** Sends everything waiting, oldest attempt first. Returns how many were tried. */
    public int sendPending(int limit) {
        List<PayoutTransfer> waiting = mongo.find(Query.query(Criteria.where("status").is(TransferStatus.PENDING))
                .with(Sort.by("updatedAt")).limit(limit), PayoutTransfer.class);
        waiting.forEach(t -> sendOne(t.getId()));
        return waiting.size();
    }

    /**
     * Run once a minute. A claim older than its lease means a server died mid-send: we cannot know whether Razorpay
     * received it, so a person looks (never an automatic second send). Then waiting transfers are sent.
     */
    public void sweep() {
        mongo.updateMulti(Query.query(Criteria.where("status").is(TransferStatus.SENDING)
                        .and("lockedUntil").lt(clock.instant())),
                new Update().set("status", TransferStatus.NEEDS_REVIEW)
                        .set("note", "A server stopped while sending this. Check the Razorpay dashboard before sending again")
                        .set("updatedAt", clock.instant()).unset("lockedUntil"),
                PayoutTransfer.class);
        sendPending(50);
    }

    // ---------- what Razorpay tells us ----------

    /** transfer.processed or transfer.failed. entity is payload.transfer.entity. Safe to receive twice. */
    public void onTransferEvent(String event, JsonNode entity) {
        String providerId = entity.path("id").asText("");
        String reference = entity.path("notes").path("reference").asText(""); // our transfer id, sent with the request
        PayoutTransfer transfer = reference.isEmpty() ? null : mongo.findById(reference, PayoutTransfer.class);
        if (transfer == null && !providerId.isEmpty()) {
            transfer = mongo.findOne(Query.query(Criteria.where("providerTransferId").is(providerId)), PayoutTransfer.class);
        }
        if (transfer == null) {
            return; // not one of ours
        }
        Instant now = clock.instant();

        if (entity.path("amount").asLong(-1) != transfer.getAmountMinor()) {
            mongo.updateFirst(Query.query(Criteria.where("id").is(transfer.getId())
                            .and("status").nin(TransferStatus.PROCESSED, TransferStatus.SKIPPED)),
                    new Update().set("status", TransferStatus.NEEDS_REVIEW).set("updatedAt", now)
                            .set("note", "Razorpay reports a different amount than we sent: check the dashboard"),
                    PayoutTransfer.class);
            log.error("Transfer {} amount differs from Razorpay's {}", transfer.getId(), providerId);
            return;
        }

        switch (event) {
            case "transfer.processed" -> {
                mongo.updateFirst(Query.query(Criteria.where("id").is(transfer.getId())
                                .and("status").ne(TransferStatus.PROCESSED)),
                        new Update().set("status", TransferStatus.PROCESSED).set("providerTransferId", providerId)
                                .set("note", "Processed by Razorpay").set("updatedAt", now).unset("lockedUntil"),
                        PayoutTransfer.class);
                // always: a replay after a crash still completes the ledger. The call is idempotent.
                ledger.recordPayout(transfer.getHotelId(), transfer.getId(), transfer.getAmountMinor(),
                        "Transfer " + providerId, now);
            }
            case "transfer.failed" -> {
                String why = entity.path("error").path("description").asText("Razorpay reported the transfer failed");
                mongo.updateFirst(Query.query(Criteria.where("id").is(transfer.getId()).and("status")
                                .in(TransferStatus.SENDING, TransferStatus.SENT, TransferStatus.NEEDS_REVIEW)),
                        new Update().set("status", TransferStatus.FAILED).set("providerTransferId", providerId)
                                .set("note", why.length() > 200 ? why.substring(0, 200) : why)
                                .set("updatedAt", now).unset("lockedUntil"),
                        PayoutTransfer.class);
            }
            default -> { /* not a transfer event we use */ }
        }
    }

    /**
     * The payment was refunded. A transfer not yet sent is cancelled. One already sent is flagged: the money has to be
     * pulled back from the hotel's linked account in the Razorpay dashboard. Runs inside the refund's transaction.
     */
    public void markPaymentRefunded(String paymentId) {
        Instant now = clock.instant();
        mongo.updateFirst(Query.query(Criteria.where("paymentId").is(paymentId).and("status").is(TransferStatus.PENDING)),
                new Update().set("status", TransferStatus.SKIPPED).set("updatedAt", now)
                        .set("note", "The payment was refunded before this was sent"),
                PayoutTransfer.class);
        mongo.updateFirst(Query.query(Criteria.where("paymentId").is(paymentId).and("status")
                        .in(TransferStatus.SENDING, TransferStatus.SENT, TransferStatus.PROCESSED, TransferStatus.NEEDS_REVIEW)),
                new Update().set("reversalDue", true).set("updatedAt", now)
                        .set("note", "Refunded: reverse this transfer in the Razorpay dashboard"),
                PayoutTransfer.class);
    }

    // ---------- helpers ----------

    private Optional<PayoutTransfer> find(String paymentId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("paymentId").is(paymentId)), PayoutTransfer.class));
    }

    /** Only moves a transfer we still hold (SENDING). If a webhook already settled it, that answer stands. */
    private PayoutTransfer finish(PayoutTransfer claimed, TransferStatus status, String providerTransferId, String note,
                                  String linkedAccountId, boolean attempted) {
        Update update = new Update().set("status", status).set("note", note).set("updatedAt", clock.instant())
                .unset("lockedUntil");
        if (attempted) {
            update.inc("attempts", 1);
        }
        if (providerTransferId != null) {
            update.set("providerTransferId", providerTransferId);
        }
        if (linkedAccountId != null) {
            update.set("linkedAccountId", linkedAccountId);
        }
        PayoutTransfer done = mongo.findAndModify(
                Query.query(Criteria.where("id").is(claimed.getId()).and("status").is(TransferStatus.SENDING)), update,
                FindAndModifyOptions.options().returnNew(true), PayoutTransfer.class);
        return done != null ? done : mongo.findById(claimed.getId(), PayoutTransfer.class);
    }
}