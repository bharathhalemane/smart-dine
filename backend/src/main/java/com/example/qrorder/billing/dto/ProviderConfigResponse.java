package com.example.qrorder.billing.dto;

/** Never carries a secret. webhookPath is what to register in the provider's dashboard (after the server's address). */
public record ProviderConfigResponse(boolean configured, String provider, String keyId, String webhookPath) {

    public static ProviderConfigResponse none() {
        return new ProviderConfigResponse(false, null, null, null);
    }
}