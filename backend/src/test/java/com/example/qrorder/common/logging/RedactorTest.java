package com.example.qrorder.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void masksJwtsAndBearerTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.abcDEF123_-xyz";
        assertThat(Redactor.redact("token was " + jwt)).doesNotContain("eyJ").contains("[jwt]");
        assertThat(Redactor.redact("Authorization: Bearer abcdefghijklmnop")).doesNotContain("abcdefghijklmnop");
    }

    @Test
    void masksPasswordsAndSecretsInJsonAndKeyValueForms() {
        assertThat(Redactor.redact("{\"email\":\"x\",\"password\":\"Sup3r Secret!\"}"))
                .doesNotContain("Sup3r").contains("\"password\":[redacted]");
        assertThat(Redactor.redact("failed with newPassword=Hunter22 and refresh_token=abc123def"))
                .doesNotContain("Hunter22").doesNotContain("abc123def");
    }

    @Test
    void masksCookies() {
        assertThat(Redactor.redact("Cookie: qr_customer=abc123xyz; other=1")).doesNotContain("abc123xyz");
        assertThat(Redactor.redact("qr_customer=abc123xyz")).isEqualTo("qr_customer=[redacted]");
    }

    @Test
    void masksCredentialsInsideUrls() {
        String out = Redactor.redact("cannot connect to mongodb://app:HuntER2pass@db.internal:27017/qrorder");
        assertThat(out).doesNotContain("HuntER2pass").contains("mongodb://app:[redacted]@db.internal");
    }

    @Test
    void masksEmailsButKeepsTheFirstLetterAndDomainForDebugging() {
        assertThat(Redactor.redact("Could not send to owner@example.com.")).isEqualTo("Could not send to o***@example.com.");
        assertThat(Redactor.redact("a@b.com")).isEqualTo("a***@b.com");
    }

    @Test
    void masksPasswordHashesAndLongTokens() {
        assertThat(Redactor.redact("hash $2a$10$" + "a".repeat(53) + " stored")).contains("[hash]");
        assertThat(Redactor.redact("sha " + "ab12".repeat(16))).contains("[hex]");
        assertThat(Redactor.redact("link /t/" + "Ab3_".repeat(11))).contains("[token]"); // 44 characters
    }

    @Test
    void leavesOrdinaryLogLinesAlone() {
        for (String line : new String[] {
                "Order 66a1b2c3d4e5f6a7b8c9d0e1 moved to READY for table T5",
                "Platform admin 66a1b2c3d4e5f6a7b8c9d0e1 changed hotel 66a1b2c3d4e5f6a7b8c9d0e2 from ACTIVE to SUSPENDED",
                "session 3f2504e0-4f89-11d3-9a0c-0305e82c3301 closed at 2026-11-10T12:30:00Z",
                "Started QrOrderApplication in 6.2 seconds"}) {
            assertThat(Redactor.redact(line)).isEqualTo(line);
        }
        assertThat(Redactor.redact(null)).isNull();
    }
}