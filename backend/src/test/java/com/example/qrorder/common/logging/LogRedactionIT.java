package com.example.qrorder.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LogRedactionIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger("com.example.qrorder.test.Redaction");

    @Test
    void secretsNeverReachTheRealLogOutput(CapturedOutput output) {
        log.info("Login by owner@example.com with Bearer abcdefghijklmnop and password=Sup3rSecret");
        log.error("Database down",
                new IllegalStateException("cannot connect to mongodb://app:HuntER2pass@db.internal/qrorder"));

        assertThat(output.getAll())
                .contains("o***@example.com")
                .contains("[redacted]")
                .doesNotContain("owner@example.com")
                .doesNotContain("abcdefghijklmnop")
                .doesNotContain("Sup3rSecret")
                .doesNotContain("HuntER2pass");
    }
}