package com.example.qrorder.payout.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Once a minute: sends waiting transfers and flags abandoned ones. Safe on several servers: every send is a claim. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.payouts.sweep-enabled", havingValue = "true")
public class PayoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(PayoutSweeper.class);

    private final PayoutService payouts;

    public PayoutSweeper(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Scheduled(fixedDelayString = "${app.payouts.sweep-ms:60000}", initialDelayString = "${app.payouts.sweep-ms:60000}")
    public void sweep() {
        try {
            payouts.sweep();
        } catch (RuntimeException e) {
            log.warn("Payout sweep failed", e);
        }
    }
}