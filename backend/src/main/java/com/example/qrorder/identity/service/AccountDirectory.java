package com.example.qrorder.identity.service;

import com.example.qrorder.identity.model.User;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Lets other modules (the platform admin) find accounts by email without touching the user store. */
@Service
public class AccountDirectory {

    private final MongoTemplate mongo;

    public AccountDirectory(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Ids of accounts whose email contains the fragment (case-insensitive). The fragment is not a pattern. */
    public List<String> idsByEmailContaining(String fragment, int limit) {
        Query query = Query.query(Criteria.where("email")
                .regex(Pattern.compile(escape(fragment.toLowerCase()), Pattern.CASE_INSENSITIVE))).limit(limit);
        return mongo.find(query, User.class).stream().map(User::getId).toList();
    }

    static String escape(String text) {
        return text.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }
}