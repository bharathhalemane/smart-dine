package com.example.qrorder.payout.gateway;

import com.example.qrorder.billing.provider.ProviderCredentials;

/** Sends money from the platform's Razorpay balance to a hotel's linked account, out of one captured payment. */
public interface RouteGateway {

    /** Razorpay answered and refused (4xx). Nothing was transferred. */
    class Rejected extends RuntimeException {
        public Rejected(String message) { super(message, null, false, false); }
    }

    /** We could not even connect, so the request was certainly not received. Safe to try again. */
    class Unreachable extends RuntimeException {
        public Unreachable(String message, Throwable cause) { super(message, cause); }
    }

    /** The request may or may not have been received (timeout, 5xx, unreadable answer). Never retried automatically. */
    class Uncertain extends RuntimeException {
        public Uncertain(String message, Throwable cause) { super(message, cause); }
    }

    record Result(String transferId, String status) {}

    Result transfer(ProviderCredentials credentials, String providerPaymentId, String linkedAccountId, long amountMinor,
                    String currency, String reference);
}