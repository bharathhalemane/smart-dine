package com.example.qrorder.dining.service;

import com.example.qrorder.dining.model.CustomerCounter;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Atomic per-table-visit counter: one upserting $inc, so two scans can never receive the same number. */
@Service
public class CustomerNumbering {

    private static final int MAX_ATTEMPTS = 3;

    private final MongoTemplate mongo;
    private final Clock clock;

    public CustomerNumbering(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    public int next(String diningSessionId) {
        for (int attempt = 1; ; attempt++) {
            try {
                CustomerCounter counter = mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(diningSessionId)),
                        new Update().inc("seq", 1).setOnInsert("createdAt", clock.instant()),
                        FindAndModifyOptions.options().upsert(true).returnNew(true),
                        CustomerCounter.class);
                return counter.getSeq();
            } catch (DuplicateKeyException e) {
                // Two first-ever upserts for the same visit collided; the retry takes the increment path.
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }
}