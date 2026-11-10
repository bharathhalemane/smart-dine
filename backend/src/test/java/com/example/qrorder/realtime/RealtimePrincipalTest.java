package com.example.qrorder.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RealtimePrincipalTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Test
    void aStaffPrincipalExpiresWhenItsTokenDoes() {
        RealtimePrincipal staff = RealtimePrincipal.staff("u1", false, NOW.plusSeconds(60));

        assertThat(staff.isExpired(NOW)).isFalse();
        assertThat(staff.isExpired(NOW.plusSeconds(59))).isFalse();
        assertThat(staff.isExpired(NOW.plusSeconds(60))).isTrue();
        assertThat(staff.isExpired(NOW.plusSeconds(3600))).isTrue();
    }

    @Test
    void aGuestPrincipalNeverExpiresByTimeAlone() {
        RealtimePrincipal guest = RealtimePrincipal.customer("c1", "h1", "T1", "s1");

        assertThat(guest.isExpired(NOW.plusSeconds(1_000_000))).isFalse();
    }

    @Test
    void staffAndGuestsNeverShareAName() {
        assertThat(RealtimePrincipal.staff("abc", false, NOW).getName()).isEqualTo("abc");
        assertThat(RealtimePrincipal.customer("abc", "h", "T1", "s").getName()).isEqualTo("customer:abc");
    }
}