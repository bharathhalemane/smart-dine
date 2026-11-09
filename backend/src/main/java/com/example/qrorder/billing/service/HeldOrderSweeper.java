package com.example.qrorder.billing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels unpaid held orders once a minute. Off unless app.held-orders.sweep-enabled=true.
 * Safe on several servers at once: each cancel is a compare-and-set.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.held-orders.sweep-enabled", havingValue = "true")
public class HeldOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(HeldOrderSweeper.class);

    private final HeldOrderService service;

    public HeldOrderSweeper(HeldOrderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.held-orders.sweep-ms:60000}",
            initialDelayString = "${app.held-orders.sweep-ms:60000}")
    public void sweep() {
        try {
            int expired = service.sweepAll();
            if (expired > 0) {
                log.info("Cancelled {} unpaid held orders", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Held-order sweep failed", e);
        }
    }
}