package com.example.qrorder.billing.provider;

/** A hotel's keys with the provider, decrypted for one call. Never logged. */
public record ProviderCredentials(String keyId, String keySecret, String webhookSecret) {

    @Override
    public String toString() {
        return "ProviderCredentials[keyId=" + keyId + ", secrets=hidden]";
    }
}