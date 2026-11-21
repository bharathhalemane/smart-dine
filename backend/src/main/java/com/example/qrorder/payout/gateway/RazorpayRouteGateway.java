package com.example.qrorder.payout.gateway;

import com.example.qrorder.billing.provider.ProviderCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** POST /v1/payments/{id}/transfers. Razorpay documents no idempotency header, so a doubtful outcome is never retried. */
@Component
public class RazorpayRouteGateway implements RouteGateway {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String baseUrl;

    public RazorpayRouteGateway(ObjectMapper json,
                                @Value("${app.payments.razorpay.base-url:https://api.razorpay.com}") String baseUrl) {
        this.json = json;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Result transfer(ProviderCredentials credentials, String providerPaymentId, String linkedAccountId,
                           long amountMinor, String currency, String reference) {
        HttpResponse<String> response;
        try {
            String body = json.writeValueAsString(Map.of("transfers", List.of(Map.of(
                    "account", linkedAccountId, "amount", amountMinor, "currency", currency,
                    "notes", Map.of("reference", reference)))));
            String basic = Base64.getEncoder().encodeToString(
                    (credentials.keyId() + ":" + credentials.keySecret()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/payments/" + providerPaymentId + "/transfers"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + basic)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new Unreachable("Could not connect to Razorpay", e);
        } catch (IOException e) {
            throw new Uncertain("Razorpay did not answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Uncertain("Interrupted while calling Razorpay", e);
        }

        int code = response.statusCode();
        if (code / 100 == 4) {
            throw new Rejected("Razorpay refused the transfer (HTTP " + code + "): " + describe(response.body()));
        }
        if (code / 100 != 2) {
            throw new Uncertain("Razorpay answered HTTP " + code, null);
        }
        try {
            JsonNode item = json.readTree(response.body()).path("items").path(0);
            String id = item.path("id").asText("");
            if (id.isBlank()) {
                throw new Uncertain("Razorpay answered without a transfer id", null);
            }
            String status = item.path("status").asText(item.path("transfer_status").asText(""));
            return new Result(id, status);
        } catch (IOException e) {
            throw new Uncertain("Unreadable answer from Razorpay", e);
        }
    }

    /** Razorpay's error description, shortened. Never the request, which holds no secrets but keep logs small. */
    private String describe(String body) {
        try {
            String text = json.readTree(body).path("error").path("description").asText("");
            return text.length() > 200 ? text.substring(0, 200) : text;
        } catch (IOException e) {
            return "";
        }
    }
}