package com.example.qrorder.admin.service;

import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.payout.model.HotelPayoutAccount;
import java.time.Clock;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only place a hotel's payout account changes. The change and its audit entry commit together. */
@Service
public class PayoutAccountWriter {

    private final MongoTemplate mongo;
    private final Clock clock;

    public PayoutAccountWriter(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Transactional
    public void apply(String hotelId, String linkedAccountId, String userId, AdminAuditEntry audit) {
        mongo.upsert(Query.query(Criteria.where("hotelId").is(hotelId)),
                new Update().set("linkedAccountId", linkedAccountId).set("updatedBy", userId).set("updatedAt", clock.instant()),
                HotelPayoutAccount.class);
        mongo.insert(audit);
    }
}