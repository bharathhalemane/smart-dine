package com.example.qrorder.billing.provider;

/** What a payment provider must do for us. Razorpay is the first; another one is another implementation. */
public interface PaymentProvider {

    String id();

    /** Asks the provider for an order the customer can pay. receipt is our own reference for it. */
    CreatedOrder createOrder(ProviderCredentials credentials, long amountMinor, String currency, String receipt);

    /** True only if signature is the provider's signature of exactly these bytes. */
    boolean verifyWebhook(ProviderCredentials credentials, byte[] rawBody, String signature);
}