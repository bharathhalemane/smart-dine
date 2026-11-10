package com.example.qrorder.admin.service;

import com.example.qrorder.admin.dto.PlatformStats;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.service.AccountDirectory;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Platform-wide counts. Orders and sessions are counted by collection name so this module stays independent. */
@Service
public class PlatformStatsService {

    private final MongoTemplate mongo;
    private final AccountDirectory accounts;
    private final Clock clock;

    public PlatformStatsService(MongoTemplate mongo, AccountDirectory accounts, Clock clock) {
        this.mongo = mongo;
        this.accounts = accounts;
        this.clock = clock;
    }

    public PlatformStats stats() {
        Instant now = clock.instant();
        Instant day = now.minus(Duration.ofHours(24));
        Instant week = now.minus(Duration.ofDays(7));
        Instant month = now.minus(Duration.ofDays(30));

        PlatformStats.Hotels hotels = new PlatformStats.Hotels(
                mongo.count(new Query(), Hotel.class),
                hotelsIn(HotelStatus.ACTIVE), hotelsIn(HotelStatus.SUSPENDED), hotelsIn(HotelStatus.DEACTIVATED),
                hotelsCreatedSince(week), hotelsCreatedSince(month));

        PlatformStats.Users users = new PlatformStats.Users(accounts.countUsers(),
                accounts.countUsersByStatus(UserStatus.ACTIVE), accounts.countUsersByStatus(UserStatus.DISABLED),
                accounts.countPlatformAdmins());

        Query weekOrders = Query.query(Criteria.where("createdAt").gte(week));
        PlatformStats.Activity activity = new PlatformStats.Activity(
                mongo.count(Query.query(Criteria.where("active").is(true)), "diningSessions"),
                mongo.count(Query.query(Criteria.where("createdAt").gte(day)), "orders"),
                mongo.count(weekOrders, "orders"),
                mongo.findDistinct(weekOrders, "hotelId", "orders", String.class).size());

        return new PlatformStats(now, hotels, users, activity);
    }

    private long hotelsIn(HotelStatus status) {
        return mongo.count(Query.query(Criteria.where("status").is(status)), Hotel.class);
    }

    private long hotelsCreatedSince(Instant since) {
        return mongo.count(Query.query(Criteria.where("createdAt").gte(since)), Hotel.class);
    }
}