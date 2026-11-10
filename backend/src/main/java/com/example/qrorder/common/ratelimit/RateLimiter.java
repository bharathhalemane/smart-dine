package com.example.qrorder.common.ratelimit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Token buckets held in memory, one per key. A full bucket allows a burst, then refills steadily. */
public final class RateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private static final int MAX_KEYS = 100_000;
    private static final long SWEEP_EVERY_CALLS = 10_000;

    private static final class Bucket {
        double tokens;
        volatile long last;
        final long windowNanos;

        Bucket(double tokens, long last, long windowNanos) {
            this.tokens = tokens;
            this.last = last;
            this.windowNanos = windowNanos;
        }
    }

    private final LongSupplier nanos;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    public RateLimiter(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /** Takes one token from the key's bucket (capacity = limit, refilled evenly over the window). */
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = nanos.getAsLong();
        long windowNanos = window.toNanos();
        if (calls.incrementAndGet() % SWEEP_EVERY_CALLS == 0) {
            sweepIdle();
        }
        Decision[] result = new Decision[1];
        buckets.compute(key, (k, existing) -> {
            Bucket b = existing;
            if (b == null) {
                if (buckets.size() >= MAX_KEYS) { // table full of live clients: let this one through untracked
                    result[0] = Decision.ALLOWED;
                    return null;
                }
                b = new Bucket(limit, now, windowNanos);
            }
            double rate = (double) limit / windowNanos; // tokens per nanosecond
            b.tokens = Math.min(limit, b.tokens + Math.max(0, now - b.last) * rate);
            b.last = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                result[0] = Decision.ALLOWED;
            } else {
                long waitNanos = (long) Math.ceil((1 - b.tokens) / rate);
                result[0] = new Decision(false, Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L));
            }
            return b;
        });
        return result[0];
    }

    /** Forgets buckets idle for a whole window: they would have refilled to full anyway. */
    public void sweepIdle() {
        long now = nanos.getAsLong();
        buckets.entrySet().removeIf(e -> now - e.getValue().last >= e.getValue().windowNanos);
    }

    public int trackedKeys() {
        return buckets.size();
    }
}