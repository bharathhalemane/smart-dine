package com.example.qrorder.admin.service;

import com.example.qrorder.admin.dto.HealthInfo;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import org.springframework.boot.SpringBootVersion;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** A quick look at whether the platform's own parts are working. */
@Service
public class PlatformHealthService {

    private final MongoTemplate mongo;
    private final Clock clock;

    public PlatformHealthService(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    public HealthInfo check() {
        HealthInfo.Database database = pingDatabase();
        java.lang.Runtime jvm = java.lang.Runtime.getRuntime();
        long mb = 1024 * 1024;
        HealthInfo.Runtime runtime = new HealthInfo.Runtime(
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                (jvm.totalMemory() - jvm.freeMemory()) / mb, jvm.maxMemory() / mb, jvm.availableProcessors(),
                System.getProperty("java.version"), SpringBootVersion.getVersion());
        return new HealthInfo(database.up() ? "UP" : "DEGRADED", clock.instant(), database, runtime);
    }

    private HealthInfo.Database pingDatabase() {
        long started = System.nanoTime();
        try {
            mongo.executeCommand("{ ping: 1 }");
            return new HealthInfo.Database(true, (System.nanoTime() - started) / 1_000_000, null);
        } catch (RuntimeException e) {
            return new HealthInfo.Database(false, (System.nanoTime() - started) / 1_000_000,
                    e.getClass().getSimpleName());
        }
    }
}