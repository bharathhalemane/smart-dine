package com.example.qrorder.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);
    private final RateLimiter limiter = new RateLimiter(now::get);

    @Test
    void allowsTheBurstThenRefuses() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isFalse();
    }

    @Test
    void saysHowLongToWaitAndRefillsAtThatPace() {
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("k", 6, MINUTE); // 6 per minute = one token every 10 seconds
        }
        RateLimiter.Decision refused = limiter.tryAcquire("k", 6, MINUTE);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(10L, 11L);

        now.addAndGet(11 * SECOND);
        assertThat(limiter.tryAcquire("k", 6, MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 6, MINUTE).allowed()).isFalse();
    }

    @Test
    void keysDoNotAffectEachOther() {
        for (int i = 0; i < 2; i++) {
            limiter.tryAcquire("a", 2, MINUTE);
        }
        assertThat(limiter.tryAcquire("a", 2, MINUTE).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 2, MINUTE).allowed()).isTrue();
    }

    @Test
    void aLongRestOnlyRefillsToTheLimitNotBeyond() {
        limiter.tryAcquire("k", 2, MINUTE);
        now.addAndGet(3600 * SECOND);
        assertThat(limiter.tryAcquire("k", 2, MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 2, MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 2, MINUTE).allowed()).isFalse();
    }

    @Test
    void idleKeysAreForgotten() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("key" + i, 3, MINUTE);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(5);
        limiter.sweepIdle();
        assertThat(limiter.trackedKeys()).isEqualTo(5); // not idle long enough yet

        now.addAndGet(61 * SECOND);
        limiter.sweepIdle();
        assertThat(limiter.trackedKeys()).isZero();
    }
}