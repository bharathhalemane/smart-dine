package com.example.qrorder.billing.config;

import com.example.qrorder.billing.provider.ProviderCredentials;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The platform's own Razorpay account (Route). Empty means platform collection is switched off. */
@ConfigurationProperties(prefix = "app.payments.platform.razorpay")
public record PlatformRazorpayProperties(
        @DefaultValue("") String keyId,
        @DefaultValue("") String keySecret,
        @DefaultValue("") String webhookSecret) {

    public boolean enabled() {
        return !keyId.isBlank() && !keySecret.isBlank() && !webhookSecret.isBlank();
    }

    public ProviderCredentials credentials() {
        return new ProviderCredentials(keyId.strip(), keySecret.strip(), webhookSecret.strip());
    }

    @Override
    public String toString() {
        return "PlatformRazorpayProperties[keyId=" + keyId + ", secrets=hidden]";
    }
}