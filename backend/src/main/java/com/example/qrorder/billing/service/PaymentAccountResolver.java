package com.example.qrorder.billing.service;

import com.example.qrorder.billing.config.PlatformRazorpayProperties;
import com.example.qrorder.billing.model.HotelPaymentProvider;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.provider.ProviderCredentials;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Which Razorpay account takes this hotel's online payments: its own keys if it saved any, else the platform's. */
@Service
public class PaymentAccountResolver {

    public record Account(String provider, ProviderCredentials credentials, boolean platform) {}

    private final ProviderConfigService providerConfigs;
    private final PlatformRazorpayProperties platform;

    public PaymentAccountResolver(ProviderConfigService providerConfigs, PlatformRazorpayProperties platform) {
        this.providerConfigs = providerConfigs;
        this.platform = platform;
    }

    public Optional<Account> forHotel(String hotelId) {
        Optional<HotelPaymentProvider> own = providerConfigs.find(hotelId);
        if (own.isPresent()) {
            return Optional.of(new Account(own.get().getProvider(), providerConfigs.credentials(own.get()), false));
        }
        if (platform.enabled()) {
            return Optional.of(new Account(ProviderConfigService.RAZORPAY, platform.credentials(), true));
        }
        return Optional.empty();
    }

    public Optional<ProviderCredentials> platformCredentials() {
        return platform.enabled() ? Optional.of(platform.credentials()) : Optional.empty();
    }

    /** The public key id the browser needs to open Razorpay's checkout for this attempt. */
    public Optional<String> keyIdFor(OnlinePayment attempt) {
        if (attempt.isPlatformAccount()) {
            return platformCredentials().map(ProviderCredentials::keyId);
        }
        return providerConfigs.find(attempt.getHotelId()).map(HotelPaymentProvider::getKeyId);
    }
}