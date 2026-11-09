package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.PaymentReceipt;
import com.example.qrorder.billing.dto.PaymentResponse;
import com.example.qrorder.billing.dto.RecordPaymentRequest;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.model.PaymentStatus;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final int MAX_ATTEMPTS = 10;

    /** created is false when this call returned a payment that an earlier identical request had made. */
    public record Recorded(PaymentReceipt receipt, boolean created) {}

    private final PaymentRepository payments;
    private final PaymentWriter writer;
    private final DiningSessionRepository sessions;
    private final BillService bills;
    private final BillRepository billRepository;
    private final PaymentSettingsService settings;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentWriter writer, DiningSessionRepository sessions,
                          BillService bills, BillRepository billRepository, PaymentSettingsService settings,
                          Clock clock) {
        this.payments = payments;
        this.writer = writer;
        this.sessions = sessions;
        this.bills = bills;
        this.billRepository = billRepository;
        this.settings = settings;
        this.clock = clock;
    }

    // ---------- record ----------

    public Recorded record(TenantContext tenant, String sessionId, String idempotencyKey,
                           RecordPaymentRequest request) {
        tenant.require(Permission.TAKE_PAYMENT);
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw validation("Send an Idempotency-Key header of 16 to 64 characters: letters, digits, _ or -");
        }
        if (request.method() == PaymentMethod.ONLINE) {
            throw validation("Online payments are taken through the pay link, not recorded by staff");
        }
        DiningSession session = load(tenant, sessionId);
        String requestHash = hash(request);

        // A retry of a payment that already exists returns it, even if the table has since been cleared.
        Optional<Payment> existing = payments.findByDiningSessionIdAndIdempotencyKey(sessionId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        if (!session.isActive()) {
            throw DomainException.invalidTransition("This table has been cleared, so no more payments can be recorded");
        }
        PaymentSettings accepted = settings.effective(tenant.hotelId());
        if (!accepted.accepts(request.method())) {
            throw validation("This hotel does not accept " + request.method() + " payments");
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Bill bill = bills.currentBill(session);
            long paid = bill.getPaidMinor();
            long due = Math.max(0, bill.getTotalMinor() - paid);
            long amount = request.amountMinor();
            if (amount > due) {
                throw DomainException.conflict("That is more than the amount due (" + money(due, bill) + ")");
            }
            if (!accepted.isPartialPaymentsAllowed() && amount != due) {
                throw validation("This hotel only takes the full amount due (" + money(due, bill) + ") in one payment");
            }

            Payment payment = new Payment(tenant.hotelId(), sessionId, bill.getId(), request.method(), amount,
                    clean(request.reference()), clean(request.note()), clean(request.payerLabel()), tenant.userId(),
                    clock.instant(), idempotencyKey, requestHash);
            try {
                return new Recorded(receipt(writer.record(payment, bill.getId(), paid)), true);
            } catch (PaymentWriter.StaleBillException stale) {
                // the bill moved between our read and our write: look at it again
            } catch (DuplicateKeyException race) {
                Payment winner = payments.findByDiningSessionIdAndIdempotencyKey(sessionId, idempotencyKey)
                        .orElseThrow(() -> race);
                return replay(winner, requestHash);
            } catch (RuntimeException e) {
                if (!isTransient(e)) {
                    throw e;
                }
                pause(attempt);
            }
        }
        throw DomainException.conflict("The bill is changing, please try again");
    }

    // ---------- list ----------

    public List<PaymentResponse> list(TenantContext tenant, String sessionId) {
        tenant.requireAnyPermission(Permission.TAKE_PAYMENT, Permission.DELIVER_ORDERS);
        load(tenant, sessionId);
        return payments.findByDiningSessionIdOrderByReceivedAtAscIdAsc(sessionId).stream()
                .map(PaymentResponse::from).toList();
    }

    // ---------- void ----------

    public PaymentReceipt voidPayment(TenantContext tenant, String sessionId, String paymentId, String reason) {
        tenant.require(Permission.ADJUST_BILL);
        DiningSession session = load(tenant, sessionId);
        if (!session.isActive()) {
            throw DomainException.invalidTransition("This table has been cleared, so its payments can no longer be changed");
        }
        Payment target = findPayment(tenant, sessionId, paymentId);
        if (target.getMethod() == PaymentMethod.ONLINE) {
            throw DomainException.conflict("Online payments are refunded from the payment provider, not voided here");
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Payment voided = writer.voidPayment(tenant.hotelId(), paymentId, tenant.userId(), reason.strip(),
                        clock.instant());
                if (voided != null) {
                    return receipt(voided);
                }
                Payment current = findPayment(tenant, sessionId, paymentId);
                if (current.getStatus() == PaymentStatus.VOIDED) {
                    return receipt(current); // someone else (or a double-tap) already did it
                }
                throw DomainException.conflict("This payment was just changed, please try again");
            } catch (RuntimeException e) {
                if (!isTransient(e)) {
                    throw e;
                }
                pause(attempt);
            }
        }
        throw DomainException.conflict("The bill is changing, please try again");
    }

    // ---------- helpers ----------

    private DiningSession load(TenantContext tenant, String sessionId) {
        return sessions.findByIdAndHotelId(sessionId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Session"));
    }

    private Payment findPayment(TenantContext tenant, String sessionId, String paymentId) {
        return payments.findByIdAndHotelId(paymentId, tenant.hotelId())
                .filter(p -> p.getDiningSessionId().equals(sessionId))
                .orElseThrow(() -> DomainException.notFound("Payment"));
    }

    private Recorded replay(Payment existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw DomainException.conflict("This Idempotency-Key was already used for a different payment");
        }
        return new Recorded(receipt(existing), false);
    }

    private PaymentReceipt receipt(Payment payment) {
        Bill bill = billRepository.findByDiningSessionId(payment.getDiningSessionId()).orElseThrow();
        return new PaymentReceipt(PaymentResponse.from(payment), bill.getCurrency(), bill.getTotalMinor(),
                bill.getPaidMinor(), Math.max(0, bill.getTotalMinor() - bill.getPaidMinor()),
                Math.max(0, bill.getPaidMinor() - bill.getTotalMinor()));
    }

    private static String hash(RecordPaymentRequest r) {
        return Tokens.sha256(r.method() + "|" + r.amountMinor() + "|" + nullToEmpty(clean(r.reference())) + "|"
                + nullToEmpty(clean(r.note())) + "|" + nullToEmpty(clean(r.payerLabel())));
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String money(long minor, Bill bill) {
        return UpiLinks.rupees(minor) + " " + bill.getCurrency();
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }

    /** A write conflict between two transactions: safe to try again. */
    static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MongoException mongo
                    && (mongo.hasErrorLabel("TransientTransactionError") || mongo.getCode() == 112
                    || mongo.getCode() == 251)) {
                return true;
            }
        }
        return false;
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(10L * (attempt + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw DomainException.conflict("The request was interrupted, please try again");
        }
    }
}