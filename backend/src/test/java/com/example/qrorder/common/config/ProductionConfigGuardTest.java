package com.example.qrorder.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductionConfigGuardTest {

    private static Map<String, String> good() {
        Map<String, String> m = new HashMap<>();
        m.put("app.customer.cookie-secure", "true");
        m.put("app.cors.allowed-origins", "https://app.example.org,https://admin.example.org");
        m.put(ProductionConfigGuard.JWT_SECRET, "k9Vq2LmXr7TnB4eYc8WdH1sUaJ6gPzF0oQiN5vRtEyKb3MhA");
        m.put("spring.data.mongodb.uri", "mongodb://mongo:27017/qrorder?replicaSet=rs0");
        return m;
    }

    private static List<String> problems(Map<String, String> m) {
        return ProductionConfigGuard.problems(m::get);
    }

    @Test
    void aSafeConfigurationHasNoProblems() {
        assertThat(problems(good())).isEmpty();
        Map<String, String> atlas = good();
        atlas.put("spring.data.mongodb.uri", "mongodb+srv://u:p@cluster.example.net/qrorder");
        assertThat(problems(atlas)).isEmpty();
    }

    @Test
    void theCookieMustBeSecure() {
        Map<String, String> m = good();
        m.put("app.customer.cookie-secure", "false");
        assertThat(problems(m)).anyMatch(p -> p.contains("cookie-secure"));
        m.remove("app.customer.cookie-secure");
        assertThat(problems(m)).anyMatch(p -> p.contains("cookie-secure"));
    }

    @Test
    void corsOriginsMustBeExactHttpsOriginsAndNotLocal() {
        for (String bad : List.of("http://app.example.org", "https://localhost:5173", "https://127.0.0.1", "*", "")) {
            Map<String, String> m = good();
            m.put("app.cors.allowed-origins", bad);
            assertThat(problems(m)).as(bad).isNotEmpty();
        }
    }

    @Test
    void theJwtSecretMustBeLongAndNotAPlaceholder() {
        for (String bad : List.of("short", "change-me-change-me-change-me-change-me", "dev-secret-dev-secret-dev-secret-0123")) {
            Map<String, String> m = good();
            m.put(ProductionConfigGuard.JWT_SECRET, bad);
            assertThat(problems(m)).as(bad).isNotEmpty();
        }
        Map<String, String> missing = good();
        missing.remove(ProductionConfigGuard.JWT_SECRET);
        assertThat(problems(missing)).isNotEmpty();
    }

    @Test
    void mongoMustBeAReplicaSet() {
        Map<String, String> m = good();
        m.put("spring.data.mongodb.uri", "mongodb://mongo:27017/qrorder");
        assertThat(problems(m)).anyMatch(p -> p.contains("replica set"));
    }

    @Test
    void everyProblemIsReportedInOneGo() {
        assertThat(problems(new HashMap<>())).hasSize(4);
    }
}