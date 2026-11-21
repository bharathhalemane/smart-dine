package com.example.qrorder.admin.service;

import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import java.time.Clock;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Puts a FAILED or NEEDS_REVIEW transfer back to PENDING and records who did it, in one transaction. */
@Service
public class PayoutRetryWriter {

    private final MongoTemplate mongo;
    private final Clock clock;

    public PayoutRetryWriter(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Transactional
    public void reset(String transferId, TransferStatus from, AdminAuditEntry audit) {
        PayoutTransfer reset = mongo.findAndModify(
                Query.query(Criteria.where("id").is(transferId).and("status").is(from)),
                new Update().set("status", TransferStatus.PENDING).set("note", "Retry requested by a platform admin")
                        .set("updatedAt", clock.instant()),
                FindAndModifyOptions.options().returnNew(true), PayoutTransfer.class);
        if (reset == null) {
            throw DomainException.conflict("This transfer just changed. Reload and look again");
        }
        mongo.insert(audit);
    }
}