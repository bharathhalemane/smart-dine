package com.example.qrorder.earnings;

import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.tenancy.model.Hotel;
import java.time.Instant;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

final class Fixture {
    final String hotelId;
    final String sessionId;
    final String billId;

    private Fixture(String hotelId, String sessionId, String billId) {
        this.hotelId = hotelId;
        this.sessionId = sessionId;
        this.billId = billId;
    }

    /** A hotel, an active dining session and a bill with the given total and nothing paid. Unique per call. */
    static Fixture create(MongoTemplate mongo, long totalMinor) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        String hotelId = mongo.insert(new Hotel("wiring-" + tag, "Wiring " + tag, "x", null, "Asia/Kolkata", "INR", now))
                .getId();
        String sessionId = mongo.insert(new DiningSession(hotelId, "t-" + tag, "T1", now)).getId();

        Document bill = new Document()
                .append("hotelId", hotelId)
                .append("diningSessionId", sessionId)
                .append("currency", "INR")
                .append("subtotalMinor", totalMinor)
                .append("totalMinor", totalMinor)
                .append("paidMinor", 0L)
                .append("revision", 1)
                .append("createdAt", java.util.Date.from(now))
                .append("updatedAt", java.util.Date.from(now));
        mongo.insert(bill, "bills");
        String billId = bill.getObjectId("_id").toHexString();
        return new Fixture(hotelId, sessionId, billId);
    }
}