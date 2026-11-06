package com.example.qrorder.dining.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.dining.dto.SessionDetailResponse;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.DiningSession.StatusChange;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class SessionLifecycleService {

    private static final int MAX_CLOSE_ATTEMPTS = 3;

    private final MongoTemplate mongo;
    private final DiningSessionRepository sessions;
    private final ObjectProvider<SessionCloseCheck> closeChecks;
    private final Clock clock;

    public SessionLifecycleService(MongoTemplate mongo, DiningSessionRepository sessions,
                                   ObjectProvider<SessionCloseCheck> closeChecks, Clock clock) {
        this.mongo = mongo;
        this.sessions = sessions;
        this.closeChecks = closeChecks;
        this.clock = clock;
    }

    public SessionDetailResponse requestBill(TenantContext tenant, String sessionId) {
        tenant.requireAnyPermission(Permission.DELIVER_ORDERS, Permission.TAKE_PAYMENT);
        return SessionDetailResponse.from(
                transition(tenant, sessionId, SessionStatus.OPEN, SessionStatus.BILL_REQUESTED));
    }

    public SessionDetailResponse reopen(TenantContext tenant, String sessionId) {
        tenant.requireAnyPermission(Permission.DELIVER_ORDERS, Permission.TAKE_PAYMENT);
        return SessionDetailResponse.from(
                transition(tenant, sessionId, SessionStatus.BILL_REQUESTED, SessionStatus.OPEN));
    }

    /** Clears the table: closes the session from OPEN or BILL_REQUESTED. */
    public SessionDetailResponse close(TenantContext tenant, String sessionId) {
        tenant.require(Permission.CLEAR_TABLE);

        for (int attempt = 0; attempt < MAX_CLOSE_ATTEMPTS; attempt++) {
            DiningSession current = sessions.findByIdAndHotelId(sessionId, tenant.hotelId())
                    .orElseThrow(() -> DomainException.notFound("Session"));
            if (current.getStatus() == SessionStatus.CLOSED) {
                throw DomainException.invalidTransition("This session is already closed");
            }
            closeChecks.orderedStream().forEach(check -> check.check(tenant, current));

            DiningSession closed = compareAndSet(tenant, sessionId, current.getStatus(), SessionStatus.CLOSED);
            if (closed != null) {
                return SessionDetailResponse.from(closed);
            }
            // The status changed between our read and our update (e.g. the bill was requested): read again.
        }
        throw DomainException.conflict("The session keeps changing, please try again");
    }

    DiningSession transition(TenantContext tenant, String sessionId, SessionStatus expected, SessionStatus next) {
        if (!expected.canTransitionTo(next)) {
            throw new IllegalStateException("Transition " + expected + " -> " + next + " is not in the state machine");
        }

        Query query = Query.query(Criteria.where("id").is(sessionId)
                .and("hotelId").is(tenant.hotelId())
                .and("status").is(expected));
        Update update = new Update()
                .set("status", next)
                .push("statusHistory", new StatusChange(expected, next, tenant.userId(), clock.instant()))
                .inc("version", 1); // keeps @Version honest for any later save()

        DiningSession updated = mongo.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), DiningSession.class);
        if (updated != null) {
            return updated;
        }

        // No match: either there is no such session in this hotel, or its status is not what we expected.
        DiningSession current = sessions.findByIdAndHotelId(sessionId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Session"));
        throw DomainException.invalidTransition(
                "Cannot move a session from " + current.getStatus() + " to " + next);
    }

    private DiningSession compareAndSet(TenantContext tenant, String sessionId,
                                        SessionStatus expected, SessionStatus next) {
        if (!expected.canTransitionTo(next)) {
            throw new IllegalStateException("Transition " + expected + " -> " + next + " is not in the state machine");
        }
        Instant now = clock.instant();

        Query query = Query.query(Criteria.where("id").is(sessionId)
                .and("hotelId").is(tenant.hotelId())
                .and("status").is(expected));
        Update update = new Update()
                .set("status", next)
                .push("statusHistory", new StatusChange(expected, next, tenant.userId(), now))
                .inc("version", 1); // keeps @Version honest for any later save()
        if (next == SessionStatus.CLOSED) {
            update.set("active", false).set("closedAt", now); // frees the table for the next scan
        }

        return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), DiningSession.class);
    }
}