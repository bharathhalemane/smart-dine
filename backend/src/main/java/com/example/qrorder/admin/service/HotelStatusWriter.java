package com.example.qrorder.admin.service;

import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.tenancy.event.HotelStatusChanged;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelStatus;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place a hotel's status changes. A separate bean so the transaction is a real proxy call:
 * the status change and its audit entry commit together or not at all.
 */
@Service
public class HotelStatusWriter {

    private final MongoTemplate mongo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public HotelStatusWriter(MongoTemplate mongo, ApplicationEventPublisher events, Clock clock) {
        this.mongo = mongo;
        this.events = events;
        this.clock = clock;
    }

    /** Moves the hotel only if it is still in the status the caller saw. Null if someone changed it first. */
    @Transactional
    public Hotel apply(String hotelId, HotelStatus from, HotelStatus to, String statusReason, AdminAuditEntry audit) {
        Hotel updated = mongo.findAndModify(
                Query.query(Criteria.where("id").is(hotelId).and("status").is(from)),
                new Update().set("status", to).set("statusReason", statusReason)
                        .set("updatedAt", clock.instant()).inc("version", 1),
                FindAndModifyOptions.options().returnNew(true), Hotel.class);
        if (updated == null) {
            return null;
        }
        mongo.insert(audit);
        events.publishEvent(new HotelStatusChanged(hotelId, from, to));
        return updated;
    }
}