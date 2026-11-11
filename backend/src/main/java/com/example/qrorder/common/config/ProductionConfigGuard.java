package com.example.qrorder.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Refuses to start in the prod profile with a configuration that is unsafe on the public internet. */
@Component
@Profile("prod")
class ProductionConfigGuard {

    /** The property holding the JWT signing secret. CHECK: set this to the key your application.yml really uses. */
    static final String JWT_SECRET = "app.jwt.secret";

    private static final List<String> WEAK_WORDS =
            List.of("change", "example", "default", "password", "dev-", "secret-key");

    ProductionConfigGuard(Environment environment) {
        List<String> problems = problems(environment::getProperty);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration:\n - " + String.join("\n - ", problems));
        }
    }

    static List<String> problems(UnaryOperator<String> prop) {
        List<String> out = new ArrayList<>();

        if (!"true".equalsIgnoreCase(prop.apply("app.customer.cookie-secure"))) {
            out.add("app.customer.cookie-secure must be true (the guest cookie must only travel over HTTPS)");
        }

        String origins = prop.apply("app.cors.allowed-origins");
        if (origins == null || origins.isBlank()) {
            out.add("app.cors.allowed-origins is empty (set APP_CORS_ALLOWED_ORIGINS to your https frontend origins)");
        } else {
            for (String origin : origins.split(",")) {
                String o = origin.trim().toLowerCase();
                if (!o.startsWith("https://") || o.contains("localhost") || o.contains("127.0.0.1") || o.contains("*")) {
                    out.add("CORS origin '" + origin.trim() + "' is not allowed in production (must be an exact https origin, no localhost or wildcard)");
                }
            }
        }

        String secret = prop.apply(JWT_SECRET);
        if (secret == null || secret.isBlank()) {
            out.add(JWT_SECRET + " is not set (if your JWT secret uses another property name, update JWT_SECRET in this class)");
        } else {
            String lower = secret.toLowerCase();
            if (secret.length() < 32) {
                out.add("the JWT secret is shorter than 32 characters");
            }
            if (WEAK_WORDS.stream().anyMatch(lower::contains)) {
                out.add("the JWT secret looks like a placeholder or development value");
            }
        }

        String mongo = prop.apply("spring.data.mongodb.uri");
        if (mongo == null || mongo.isBlank()
                || !(mongo.contains("replicaSet=") || mongo.startsWith("mongodb+srv://"))) {
            out.add("spring.data.mongodb.uri must point at a replica set (transactions need one): add replicaSet=... or use mongodb+srv://");
        }
        return out;
    }
}