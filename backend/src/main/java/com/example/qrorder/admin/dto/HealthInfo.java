package com.example.qrorder.admin.dto;

import java.time.Instant;

public record HealthInfo(String status, Instant checkedAt, Database database, Runtime runtime) {

    public record Database(boolean up, long pingMillis, String error) {
    }

    public record Runtime(long uptimeSeconds, long heapUsedMb, long heapMaxMb, int processors,
                          String javaVersion, String springBootVersion) {
    }
}