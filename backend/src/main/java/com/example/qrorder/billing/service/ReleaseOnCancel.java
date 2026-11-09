package com.example.qrorder.billing.service;

import com.example.qrorder.ordering.service.OrderCancelListener;
import org.springframework.stereotype.Component;

/** A cancelled order frees the money held for it, which may now be enough for the next held order. */
@Component
public class ReleaseOnCancel implements OrderCancelListener {

    private final OrderReleaseService releaser;

    public ReleaseOnCancel(OrderReleaseService releaser) {
        this.releaser = releaser;
    }

    @Override
    public void afterCancelled(String hotelId, String diningSessionId) {
        releaser.releaseForSession(hotelId, diningSessionId);
    }
}