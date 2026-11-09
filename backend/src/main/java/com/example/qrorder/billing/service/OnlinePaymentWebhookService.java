package com.example.qrorder.billing.service;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.HotelPaymentProvider;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.provider.PaymentProviders;
import com.example.qrorder.billing.provider.ProviderCredentials;
import com.example.qrorder.billing.repository.OnlinePaymentRepository;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Turns the provider's "payment captured" call into money on the bill. Every outcome except a temporary failure
 * is final and answered with 200, because the provider retries anything else. Money that cannot be counted is
 * parked as NEEDS_REVIEW, never dropped.
 */
@Service
public class OnlinePaymentWebhookService {

    private static final int MAX_ATTEMPTS = 10;
    static final String RECEIVER = "online:razorpay";

    private final ProviderConfigService providerConfigs;
    private final PaymentProviders providers;
    private final OnlinePaymentRepository attempts;
    private final DiningSessionRepository sessions;
    private final BillService bills;
    private final PaymentWriter writer;
    private final MongoTemplate mongo;
    private final ObjectMapper json;
    private final OrderReleaseService releaser;
    private final Clock clock;

    public OnlinePaymentWebhookService(ProviderConfigService providerConfigs, PaymentProviders providers,
                                       OnlinePaymentRepository attempts, DiningSessionRepository sessions,
                                       BillService bills, PaymentWriter writer, MongoTemplate mongo,
                                       ObjectMapper json, Clock clock, OrderReleaseService releaser) {
        this.providerConfigs = providerConfigs;
        this.providers = providers;
        this.attempts = attempts;
        this.sessions = sessions;
        this.bills = bills;
        this.writer = writer;
        this.mongo = mongo;
        this.json = json;
        this.clock = clock;
        this.releaser = releaser;
    }

    public void handle(String hookKey, byte[] rawBody, String signature) {
        HotelPaymentProvider config = providerConfigs.findByHookKey(hookKey)
                .orElseThrow(() -> DomainException.notFound("Webhook"));
        ProviderCredentials credentials = providerConfigs.credentials(config);
        if (!providers.get(config.getProvider()).verifyWebhook(credentials, rawBody, signature)) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid signature");
        }

        JsonNode root;
        try {
            root = json.readTree(rawBody);
        } catch (IOException e) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unreadable payload");
        }
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = payment.path("order_id").asText("");
        Optional<OnlinePayment> found = orderId.isEmpty() ? Optional.empty() : attempts.findByProviderOrderId(orderId);
        if (found.isEmpty() || !found.get().getHotelId().equals(config.getHotelId())) {
            return; // not ours, or not this hotel's: nothing to do
        }
        OnlinePayment attempt = found.get();

        switch (root.path("event").asText("")) {
            case "payment.captured", "order.paid" -> credit(attempt, payment.path("id").asText(""),
                    payment.path("amount").asLong(-1), payment.path("currency").asText(""));
            case "payment.failed" -> mongo.updateFirst(
                    Query.query(Criteria.where("id").is(attempt.getId()).and("status").is(OnlinePaymentStatus.CREATED)),
                    new Update().set("status", OnlinePaymentStatus.FAILED).set("updatedAt", clock.instant()),
                    OnlinePayment.class);
            default -> { /* an event we do not use */ }
        }
    }

    private void credit(OnlinePayment attempt, String providerPaymentId, long amount, String currency) {
        if (attempt.getStatus() == OnlinePaymentStatus.CAPTURED) {
            return; // a replay
        }
        if (amount != attempt.getAmountMinor() || !attempt.getCurrency().equalsIgnoreCase(currency)) {
            review(attempt, providerPaymentId, "The amount or currency captured differs from what was requested");
            return;
        }
        DiningSession session = sessions.findByIdAndHotelId(attempt.getDiningSessionId(), attempt.getHotelId())
                .orElse(null);
        if (session == null || !session.isActive()) {
            review(attempt, providerPaymentId, "The table was cleared before the payment arrived");
            return;
        }

        for (int tryNumber = 0; tryNumber < MAX_ATTEMPTS; tryNumber++) {
            Bill bill = bills.currentBill(session);
            long paid = bill.getPaidMinor();
            long due = Math.max(0, bill.getTotalMinor() - paid);
            if (amount > due) {
                review(attempt, providerPaymentId, "The payment is more than the amount due now");
                return;
            }
            Payment entry = new Payment(attempt.getHotelId(), attempt.getDiningSessionId(), bill.getId(),
                    PaymentMethod.ONLINE, amount, providerPaymentId, "Online payment", attempt.getPayerLabel(),
                    RECEIVER, clock.instant(), "online-" + attempt.getId(), "online");
            try {
                writer.record(entry, bill.getId(), paid);
                markCaptured(attempt, providerPaymentId);
                releaser.releaseQuietly(session);
                return;
            } catch (PaymentWriter.StaleBillException stale) {
                // the bill moved since we read it: look again
            } catch (DuplicateKeyException alreadyCounted) {
                markCaptured(attempt, providerPaymentId); // this very payment was counted by an earlier delivery
                releaser.releaseQuietly(session); // and make sure the orders it paid for were released
                return;
            } catch (RuntimeException e) {
                if (!PaymentService.isTransient(e)) {
                    throw e;
                }
                pause(tryNumber);
            }
        }
        throw DomainException.conflict("The bill is changing, please try again"); // 409: the provider retries
    }

    private void markCaptured(OnlinePayment attempt, String providerPaymentId) {
        mongo.updateFirst(
                Query.query(Criteria.where("id").is(attempt.getId())
                        .and("status").in(OnlinePaymentStatus.CREATED, OnlinePaymentStatus.NEEDS_REVIEW,
                                OnlinePaymentStatus.EXPIRED)),
                new Update().set("status", OnlinePaymentStatus.CAPTURED).set("providerPaymentId", providerPaymentId)
                        .set("updatedAt", clock.instant()),
                OnlinePayment.class);
    }

    private void review(OnlinePayment attempt, String providerPaymentId, String reason) {
        mongo.updateFirst(
                Query.query(Criteria.where("id").is(attempt.getId())
                        .and("status").in(OnlinePaymentStatus.CREATED, OnlinePaymentStatus.NEEDS_REVIEW,
                                OnlinePaymentStatus.EXPIRED)),
                new Update().set("status", OnlinePaymentStatus.NEEDS_REVIEW)
                        .set("providerPaymentId", providerPaymentId).set("reviewReason", reason)
                        .set("updatedAt", clock.instant()),
                OnlinePayment.class);
    }

    private static void pause(int tryNumber) {
        try {
            Thread.sleep(10L * (tryNumber + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw DomainException.conflict("The request was interrupted, please try again");
        }
    }
}