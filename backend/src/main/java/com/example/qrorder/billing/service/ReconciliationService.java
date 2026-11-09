package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.ExpireResult;
import com.example.qrorder.billing.dto.ReconciliationReport;
import com.example.qrorder.billing.dto.ReconciliationReport.BillDrift;
import com.example.qrorder.billing.dto.ReconciliationReport.OnlineMismatch;
import com.example.qrorder.billing.dto.ReconciliationReport.SettlementGap;
import com.example.qrorder.billing.dto.RepairResult;
import com.example.qrorder.billing.dto.RepairView;
import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentStatus;
import com.example.qrorder.billing.model.ReconciliationEntry;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Checks that every bill's running paid counter agrees with its payment ledger, and that provider money and
 * the ledger agree. Reads only; the one write that fixes anything is repair, and it only ever moves the counter
 * to what the ledger already says.
 */
@Service
public class ReconciliationService {

    static final int MAX_BILLS = 5000;
    private static final Duration GRACE = Duration.ofMinutes(5); // a payment this fresh may still be finishing
    private static final String ONLINE_KEY_PREFIX = "online-";
    private static final int MAX_REPAIR_ATTEMPTS = 5;

    private final MongoTemplate mongo;
    private final Clock clock;

    public ReconciliationService(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    // ---------- report ----------

    public ReconciliationReport report(TenantContext tenant, int days) {
        tenant.require(Permission.ADJUST_BILL);
        if (days < 1 || days > 366) {
            throw validation("days must be between 1 and 366");
        }
        String hotelId = tenant.hotelId();
        Instant now = clock.instant();
        Instant since = now.minus(days, ChronoUnit.DAYS);

        Query billQuery = Query.query(Criteria.where("hotelId").is(hotelId).orOperator(
                        Criteria.where("createdAt").gte(since), Criteria.where("updatedAt").gte(since)))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(MAX_BILLS + 1);
        List<Bill> bills = new ArrayList<>(mongo.find(billQuery, Bill.class));
        boolean truncated = bills.size() > MAX_BILLS;
        if (truncated) {
            bills = new ArrayList<>(bills.subList(0, MAX_BILLS));
        }

        Set<String> sessionIds = bills.stream().map(Bill::getDiningSessionId).collect(Collectors.toSet());
        Map<String, Long> ledger = ledgerSums(hotelId, sessionIds);
        Set<String> closed = sessionIds.isEmpty() ? Set.of() : mongo.find(
                        Query.query(Criteria.where("id").in(sessionIds).and("active").is(false)), DiningSession.class)
                .stream().map(DiningSession::getId).collect(Collectors.toSet());

        List<BillDrift> drifts = new ArrayList<>();
        List<SettlementGap> gaps = new ArrayList<>();
        for (Bill bill : bills) {
            long inLedger = ledger.getOrDefault(bill.getDiningSessionId(), 0L);
            if (bill.getPaidMinor() != inLedger) {
                drifts.add(new BillDrift(bill.getId(), bill.getDiningSessionId(), bill.getTableNumber(),
                        bill.getPaidMinor(), inLedger, bill.getPaidMinor() - inLedger));
            }
            if (closed.contains(bill.getDiningSessionId()) && bill.getTotalMinor() != bill.getPaidMinor()) {
                gaps.add(new SettlementGap(bill.getId(), bill.getDiningSessionId(), bill.getTableNumber(),
                        bill.getTotalMinor(), bill.getPaidMinor(),
                        Math.max(0, bill.getTotalMinor() - bill.getPaidMinor()),
                        Math.max(0, bill.getPaidMinor() - bill.getTotalMinor())));
            }
        }

        long stale = mongo.count(Query.query(Criteria.where("hotelId").is(hotelId)
                .and("status").is(OnlinePaymentStatus.CREATED)
                .and("createdAt").lt(now.minus(24, ChronoUnit.HOURS))), OnlinePayment.class);
        long review = mongo.count(Query.query(Criteria.where("hotelId").is(hotelId)
                .and("status").is(OnlinePaymentStatus.NEEDS_REVIEW)), OnlinePayment.class);

        return new ReconciliationReport(now, since, days, bills.size(), truncated, drifts, gaps,
                onlineMismatches(hotelId, since, now), stale, review);
    }

    private List<OnlineMismatch> onlineMismatches(String hotelId, Instant since, Instant now) {
        List<OnlineMismatch> found = new ArrayList<>();

        List<Payment> onlinePayments = mongo.find(Query.query(Criteria.where("hotelId").is(hotelId)
                .and("method").is(PaymentMethod.ONLINE).and("receivedAt").gte(since)).limit(MAX_BILLS), Payment.class);
        Set<String> ledgerKeys = onlinePayments.stream().map(Payment::getIdempotencyKey).collect(Collectors.toSet());

        // the provider says paid, but there is no ledger entry
        List<OnlinePayment> captured = mongo.find(Query.query(Criteria.where("hotelId").is(hotelId)
                .and("status").is(OnlinePaymentStatus.CAPTURED).and("updatedAt").gte(since)).limit(MAX_BILLS),
                OnlinePayment.class);
        for (OnlinePayment attempt : captured) {
            if (!ledgerKeys.contains(ONLINE_KEY_PREFIX + attempt.getId())) {
                found.add(new OnlineMismatch("CAPTURED_WITHOUT_PAYMENT", attempt.getId(),
                        attempt.getDiningSessionId(), attempt.getAmountMinor(), attempt.getProviderPaymentId()));
            }
        }

        // a ledger entry with no captured attempt behind it
        Instant settled = now.minus(GRACE);
        Set<String> attemptIds = new HashSet<>();
        for (Payment payment : onlinePayments) {
            if (payment.getStatus() == PaymentStatus.RECORDED && payment.getReceivedAt().isBefore(settled)
                    && payment.getIdempotencyKey().startsWith(ONLINE_KEY_PREFIX)) {
                attemptIds.add(payment.getIdempotencyKey().substring(ONLINE_KEY_PREFIX.length()));
            }
        }
        Map<String, OnlinePayment> attempts = attemptIds.isEmpty() ? Map.of() : mongo.find(
                        Query.query(Criteria.where("id").in(attemptIds)), OnlinePayment.class).stream()
                .collect(Collectors.toMap(OnlinePayment::getId, a -> a));
        for (Payment payment : onlinePayments) {
            if (payment.getStatus() != PaymentStatus.RECORDED || !payment.getReceivedAt().isBefore(settled)) {
                continue;
            }
            String key = payment.getIdempotencyKey();
            OnlinePayment attempt = key.startsWith(ONLINE_KEY_PREFIX)
                    ? attempts.get(key.substring(ONLINE_KEY_PREFIX.length())) : null;
            if (attempt == null || attempt.getStatus() != OnlinePaymentStatus.CAPTURED) {
                found.add(new OnlineMismatch("PAYMENT_WITHOUT_CAPTURE", attempt == null ? null : attempt.getId(),
                        payment.getDiningSessionId(), payment.getAmountMinor(), payment.getReference()));
            }
        }
        return found;
    }

    // ---------- repair ----------

    public RepairResult repair(TenantContext tenant, String billId, String reason) {
        tenant.require(Permission.ADJUST_BILL);
        String hotelId = tenant.hotelId();

        for (int attempt = 0; attempt < MAX_REPAIR_ATTEMPTS; attempt++) {
            // Order matters: the bill first, then the ledger, then a change that only applies if the bill is
            // still exactly what we read. A payment landing in between makes that change not apply, and we look again.
            Bill bill = mongo.findOne(Query.query(Criteria.where("id").is(billId).and("hotelId").is(hotelId)),
                    Bill.class);
            if (bill == null) {
                throw DomainException.notFound("Bill");
            }
            long before = bill.getPaidMinor();
            long inLedger = ledgerSums(hotelId, List.of(bill.getDiningSessionId()))
                    .getOrDefault(bill.getDiningSessionId(), 0L);
            if (before == inLedger) {
                return new RepairResult(billId, false, before, inLedger);
            }

            Criteria match = Criteria.where("id").is(billId).and("hotelId").is(hotelId);
            if (before == 0) {
                match.andOperator(new Criteria().orOperator(Criteria.where("paidMinor").is(0L),
                        Criteria.where("paidMinor").exists(false)));
            } else {
                match.and("paidMinor").is(before);
            }
            Bill updated = mongo.findAndModify(Query.query(match), new Update().set("paidMinor", inLedger),
                    FindAndModifyOptions.options().returnNew(true), Bill.class);
            if (updated != null) {
                mongo.insert(new ReconciliationEntry(hotelId, billId, bill.getDiningSessionId(),
                        bill.getTableNumber(), before, inLedger, reason.strip(), tenant.userId(), clock.instant()));
                return new RepairResult(billId, true, before, inLedger);
            }
        }
        throw DomainException.conflict("The bill keeps changing, please try again");
    }

    public List<RepairView> repairs(TenantContext tenant) {
        tenant.require(Permission.ADJUST_BILL);
        return mongo.find(Query.query(Criteria.where("hotelId").is(tenant.hotelId()))
                        .with(Sort.by(Sort.Direction.DESC, "at")).limit(50), ReconciliationEntry.class).stream()
                .map(RepairView::from).toList();
    }

    // ---------- abandoned online attempts ----------

    public ExpireResult expireStaleAttempts(TenantContext tenant, int hours) {
        tenant.require(Permission.ADJUST_BILL);
        if (hours < 1 || hours > 720) {
            throw validation("hours must be between 1 and 720");
        }
        Instant now = clock.instant();
        long expired = mongo.updateMulti(Query.query(Criteria.where("hotelId").is(tenant.hotelId())
                        .and("status").is(OnlinePaymentStatus.CREATED)
                        .and("createdAt").lt(now.minus(hours, ChronoUnit.HOURS))),
                new Update().set("status", OnlinePaymentStatus.EXPIRED).set("updatedAt", now),
                OnlinePayment.class).getModifiedCount();
        return new ExpireResult((int) expired);
    }

    // ---------- helpers ----------

    /** Sum of the payments that still count (not voided), per session. */
    private Map<String, Long> ledgerSums(String hotelId, Collection<String> sessionIds) {
        Map<String, Long> sums = new HashMap<>();
        if (sessionIds.isEmpty()) {
            return sums;
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("hotelId").is(hotelId).and("status").is(PaymentStatus.RECORDED)
                        .and("diningSessionId").in(sessionIds)),
                Aggregation.group("diningSessionId").sum("amountMinor").as("sum"));
        for (Document row : mongo.aggregate(aggregation, Payment.class, Document.class).getMappedResults()) {
            sums.put(row.getString("_id"), ((Number) row.get("sum")).longValue());
        }
        return sums;
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}