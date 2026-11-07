package com.example.qrorder.dining.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The door every order must pass. Call it inside the same transaction that inserts the order: the
 * conditional write to the session document is what makes "order accepted" and "session still open"
 * one atomic fact. A close that commits first makes the gate match nothing; a close that arrives
 * later waits for, or conflicts with, this transaction.
 */
@Service
public class SessionOrderGate {

    private final MongoTemplate mongo;
    private final DiningSessionRepository sessions;

    public SessionOrderGate(MongoTemplate mongo, DiningSessionRepository sessions) {
        this.mongo = mongo;
        this.sessions = sessions;
    }

    public void admitOrder(String hotelId, String diningSessionId) {
        Query query = Query.query(Criteria.where("id").is(diningSessionId)
                .and("hotelId").is(hotelId)
                .and("active").is(true)
                .and("status").is(SessionStatus.OPEN));
        Update update = new Update().inc("orderCount", 1).inc("version", 1);

        if (mongo.findAndModify(query, update, DiningSession.class) != null) {
            return;
        }

        // Nothing matched: closed (or never existed for this hotel), or open but the bill was requested.
        boolean stillActive = sessions.findByIdAndHotelId(diningSessionId, hotelId)
                .filter(DiningSession::isActive).isPresent();
        if (!stillActive) {
            throw new DomainException(ErrorCode.SESSION_ENDED, "This table session has ended. Scan the QR code again");
        }
        throw DomainException.conflict("Ordering is paused for this table. Please ask your server");
    }
}