package com.example.qrorder.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A hotel's account with a payment provider. Secrets are stored encrypted. hookKey makes its webhook URL unique. */
@Document("paymentProviders")
public class HotelPaymentProvider {

    @Id
    private String id;

    @Indexed(unique = true)
    private String hotelId;

    private String provider;
    private String keyId;
    private String keySecretEnc;
    private String webhookSecretEnc;

    @Indexed(unique = true)
    private String hookKey;

    private String updatedByUserId;
    private Instant updatedAt;

    protected HotelPaymentProvider() {}

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getProvider() { return provider; }
    public String getKeyId() { return keyId; }
    public String getKeySecretEnc() { return keySecretEnc; }
    public String getWebhookSecretEnc() { return webhookSecretEnc; }
    public String getHookKey() { return hookKey; }
    public String getUpdatedByUserId() { return updatedByUserId; }
    public Instant getUpdatedAt() { return updatedAt; }
}