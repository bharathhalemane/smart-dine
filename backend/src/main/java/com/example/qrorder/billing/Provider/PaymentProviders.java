package com.example.qrorder.billing.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PaymentProviders {

    private final Map<String, PaymentProvider> byId;

    public PaymentProviders(List<PaymentProvider> providers) {
        this.byId = providers.stream().collect(Collectors.toMap(PaymentProvider::id, Function.identity()));
    }

    public PaymentProvider get(String id) {
        PaymentProvider provider = byId.get(id);
        if (provider == null) {
            throw new IllegalStateException("No payment provider named " + id);
        }
        return provider;
    }
}