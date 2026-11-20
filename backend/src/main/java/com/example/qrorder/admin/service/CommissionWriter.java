package com.example.qrorder.admin.service;

import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.earnings.model.EarningsSettings;
import java.time.Clock;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only place a hotel's commission rate changes. The change and its audit entry commit together or not at all. */
@Service
public class CommissionWriter {

    private final MongoTemplate mongo;
    private final Clock clock;

    public CommissionWriter(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Transactional
    public void apply(String hotelId, int commissionBps, String userId, AdminAuditEntry audit) {
        mongo.upsert(Query.query(Criteria.where("hotelId").is(hotelId)),
                new Update().set("commissionBps", commissionBps).set("updatedBy", userId).set("updatedAt", clock.instant()),
                EarningsSettings.class);
        mongo.insert(audit);
    }
}