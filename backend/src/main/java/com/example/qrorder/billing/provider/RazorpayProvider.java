package com.example.qrorder.billing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RazorpayProvider implements PaymentProvider {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String baseUrl;

    public RazorpayProvider(ObjectMapper json,
                            @Value("${app.payments.razorpay.base-url:https://api.razorpay.com}") String baseUrl) {
        this.json = json;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String id() {
        return "razorpay";
    }

    @Override
    public CreatedOrder createOrder(ProviderCredentials credentials, long amountMinor, String currency,
                                    String receipt) {
        try {
            String body = json.writeValueAsString(Map.of("amount", amountMinor, "currency", currency,
                    "receipt", receipt));
            String basic = Base64.getEncoder().encodeToString(
                    (credentials.keyId() + ":" + credentials.keySecret()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/orders"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + basic)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ProviderException("Razorpay refused the order (HTTP " + response.statusCode() + ")");
            }
            String orderId = json.readTree(response.body()).path("id").asText("");
            if (orderId.isBlank()) {
                throw new ProviderException("Razorpay answered without an order id");
            }
            return new CreatedOrder(orderId);
        } catch (IOException e) {
            throw new ProviderException("Could not reach Razorpay", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while calling Razorpay", e);
        }
    }

    @Override
    public boolean verifyWebhook(ProviderCredentials credentials, byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(credentials.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.strip().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }
}