package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.provider.SecretBox;
import com.example.qrorder.billing.service.PaymentAccountResolver;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.FeeSource;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.payments.platform.razorpay.key-id=rzp_test_platform",
        "app.payments.platform.razorpay.key-secret=platform-secret-123456",
        "app.payments.platform.razorpay.webhook-secret=platform-whsec-123456"})
class PlatformAccountIT extends AbstractIntegrationTest {

    private static final String PLATFORM_WHSEC = "platform-whsec-123456";
    private static final String BODY = """
            {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_p1","order_id":"order_p1",
            "amount":10000,"currency":"INR","fee":236,"tax":36}}}}""";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired SecretBox secrets;
    @Autowired PaymentAccountResolver accounts;
    @Autowired EarningsEntryRepository earnings;

    String hotelId;
    String attemptId;

    @BeforeEach
    void seed() {
        for (Class<?> type : List.of(Hotel.class, DiningSession.class, Order.class, OnlinePayment.class,
                Payment.class, Bill.class, EarningsEntry.class)) {
            mongo.remove(new Query(), type);
        }
        mongo.remove(new Query(), "paymentProviders");

        Instant now = Instant.now();
        hotelId = mongo.insert(new Hotel("plat-hotel", "Plat Hotel", "x", null, "Asia/Kolkata", "INR", now)).getId();
        String sessionId = mongo.insert(new DiningSession(hotelId, "t-1", "T1", now)).getId();
        Order order = mongo.insert(new Order(hotelId, sessionId, "t-1", "T1", "cust-x", "Guest 1", "INR",
                List.of(new OrderLine("item-x", "Naan", Diet.VEG, null, null, List.of(), 10_000L, 1, 10_000L, null)),
                10_000L, null, null, now));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(order.getId())),
                new Update().set("status", OrderStatus.DELIVERED), Order.class);

        OnlinePayment attempt = new OnlinePayment(new org.bson.types.ObjectId().toHexString(), hotelId, sessionId,
                "T1", "cust-x", "Guest 1", 10_000, "INR", "razorpay", "order_p1", "idem-key-0123456789", "h", now);
        attempt.markPlatformAccount();
        attemptId = mongo.insert(attempt).getId();
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions hook(String path, String secret, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", sign(secret, body)).content(body));
    }

    private OnlinePaymentStatus statusOfAttempt() {
        return mongo.findById(attemptId, OnlinePayment.class).getStatus();
    }

    private long payments() {
        return mongo.count(new Query(), Payment.class);
    }

    @Test
    void thePlatformWebhookCountsThePaymentAndWritesTheEarningsEntry() throws Exception {
        hook("/api/v1/webhooks/razorpay-platform", PLATFORM_WHSEC, BODY).andExpect(status().isOk());

        assertThat(statusOfAttempt()).isEqualTo(OnlinePaymentStatus.CAPTURED);
        assertThat(payments()).isEqualTo(1);
        EarningsEntry entry = earnings.findAll().get(0);
        assertThat(entry.getHotelId()).isEqualTo(hotelId);
        assertThat(entry.getFeeMinor()).isEqualTo(236);
        assertThat(entry.getFeeSource()).isEqualTo(FeeSource.WEBHOOK);
    }

    @Test
    void aRepeatedDeliveryIsCountedOnce() throws Exception {
        hook("/api/v1/webhooks/razorpay-platform", PLATFORM_WHSEC, BODY).andExpect(status().isOk());
        hook("/api/v1/webhooks/razorpay-platform", PLATFORM_WHSEC, BODY).andExpect(status().isOk());

        assertThat(payments()).isEqualTo(1);
        assertThat(earnings.count()).isEqualTo(1);
    }

    @Test
    void aWrongSignatureIsRefusedAndNothingIsCounted() throws Exception {
        hook("/api/v1/webhooks/razorpay-platform", "not-the-secret", BODY).andExpect(status().isUnauthorized());

        assertThat(statusOfAttempt()).isEqualTo(OnlinePaymentStatus.CREATED);
        assertThat(payments()).isZero();
    }

    @Test
    void aHotelsOwnWebhookCannotConfirmAPlatformPayment() throws Exception {
        mongo.insert(new Document("hotelId", hotelId).append("provider", "razorpay").append("keyId", "rzp_test_own")
                .append("keySecretEnc", secrets.encrypt("own-secret-123456"))
                .append("webhookSecretEnc", secrets.encrypt("own-whsec-123456")).append("hookKey", "hk-own-1"),
                "paymentProviders");

        hook("/api/v1/webhooks/razorpay/hk-own-1", "own-whsec-123456", BODY).andExpect(status().isOk());

        assertThat(statusOfAttempt()).isEqualTo(OnlinePaymentStatus.CREATED); // ignored, not counted
        assertThat(payments()).isZero();
        assertThat(earnings.count()).isZero();
    }

    @Test
    void ownKeysWinAndOtherwiseThePlatformAccountIsUsed() {
        assertThat(accounts.forHotel(hotelId).orElseThrow().platform()).isTrue();
        assertThat(accounts.forHotel(hotelId).orElseThrow().credentials().keyId()).isEqualTo("rzp_test_platform");

        mongo.insert(new Document("hotelId", hotelId).append("provider", "razorpay").append("keyId", "rzp_test_own")
                .append("keySecretEnc", secrets.encrypt("own-secret-123456"))
                .append("webhookSecretEnc", secrets.encrypt("own-whsec-123456")).append("hookKey", "hk-own-2"),
                "paymentProviders");

        assertThat(accounts.forHotel(hotelId).orElseThrow().platform()).isFalse();
        assertThat(accounts.forHotel(hotelId).orElseThrow().credentials().keyId()).isEqualTo("rzp_test_own");
    }
}