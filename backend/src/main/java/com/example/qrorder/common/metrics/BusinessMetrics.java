package com.example.qrorder.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Two numbers an operator wants on a graph. Each is read from the database at most every 30 seconds,
 * and shows NaN (a gap) if the database cannot be reached.
 */
@Component
public class BusinessMetrics {

    private static final long CACHE_MS = 30_000;

    private static final class Cached {
        private final Supplier<Double> source;
        private volatile double value = Double.NaN;
        private volatile long readAt;

        Cached(Supplier<Double> source) {
            this.source = source;
        }

        double get() {
            long now = System.currentTimeMillis();
            if (now - readAt > CACHE_MS) {
                try {
                    value = source.get();
                } catch (RuntimeException e) {
                    value = Double.NaN;
                }
                readAt = now;
            }
            return value;
        }
    }

    public BusinessMetrics(MongoTemplate mongo, MeterRegistry registry) {
        Cached openSessions = new Cached(() -> count(mongo, "diningSessions", new Document("active", true)));
        Cached awaitingPayment = new Cached(() -> count(mongo, "orders", new Document("status", "AWAITING_PAYMENT")));

        Gauge.builder("qrorder.dining.sessions.open", openSessions::get)
                .description("Table sessions currently open").register(registry);
        Gauge.builder("qrorder.orders.awaiting.payment", awaitingPayment::get)
                .description("Held orders waiting for the guest to pay").register(registry);
    }

    private static double count(MongoTemplate mongo, String collection, Document filter) {
        return mongo.getCollection(collection).countDocuments(filter);
    }
}