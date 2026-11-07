package com.example.qrorder.dining.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Hands out guest numbers for one table visit. The _id is the dining session id. */
@Document("customerCounters")
public class CustomerCounter {

    @Id
    private String id;

    private int seq;

    @Indexed(expireAfterSeconds = 604800)
    private Instant createdAt;

    protected CustomerCounter() {}

    public String getId() { return id; }
    public int getSeq() { return seq; }
}