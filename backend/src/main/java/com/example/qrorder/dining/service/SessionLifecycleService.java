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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class SessionLifecycleService {

    private final MongoTemplate mongo;
    private final DiningSessionRepository sessions;
    private final Clock clock;

    public SessionLifecycleService(MongoTemplate mongo, DiningSessionRepository sessions, Clock clock) {
        this.mongo = mongo;
        this.sessions = sessions;
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

    /**
     * Compare-and-set on the status: "move to next, but only if it is still expected". Of two
     * simultaneous requests exactly one matches the query; the other sees null and gets a 409.
     */
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
}