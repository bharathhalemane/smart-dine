package com.example.qrorder.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.earnings.service.EarningsLedger;
import com.example.qrorder.payout.model.HotelPayoutAccount;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import com.example.qrorder.payout.service.PayoutService;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "app.payments.platform.razorpay.key-id=rzp_test_platform",
        "app.payments.platform.razorpay.key-secret=platform-secret-123456",
        "app.payments.platform.razorpay.webhook-secret=platform-whsec-123456"})
class PayoutServiceIT extends AbstractIntegrationTest {

    // ---- a stand-in for Razorpay's transfer API ----
    static final List<String> PATHS = new CopyOnWriteArrayList<>();
    static final List<String> BODIES = new CopyOnWriteArrayList<>();
    static final List<String> AUTH = new CopyOnWriteArrayList<>();
    static final AtomicInteger IDS = new AtomicInteger();
    static volatile int stubStatus = 200;
    static final HttpServer RAZORPAY = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/payments", exchange -> {
                PATHS.add(exchange.getRequestURI().getPath());
                AUTH.add(exchange.getRequestHeaders().getFirst("Authorization"));
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int code = stubStatus;
                byte[] reply = (code == 200
                        ? "{\"entity\":\"collection\",\"count\":1,\"items\":[{\"id\":\"trf_test_" + IDS.incrementAndGet()
                                + "\",\"status\":\"created\"}]}"
                        : "{\"error\":{\"description\":\"stub says no\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void razorpay(DynamicPropertyRegistry registry) {
        registry.add("app.payments.razorpay.base-url", () -> "http://127.0.0.1:" + RAZORPAY.getAddress().getPort());
    }

    @Autowired MongoTemplate mongo;
    @Autowired PayoutService payouts;
    @Autowired EarningsLedger ledger;
    @Autowired EarningsEntryRepository entries;

    String hotelId;

    @BeforeEach
    void seed() {
        for (Class<?> type : List.of(Hotel.class, EarningsEntry.class, PayoutTransfer.class, HotelPayoutAccount.class)) {
            mongo.remove(new Query(), type);
        }
        PATHS.clear();
        BODIES.clear();
        AUTH.clear();
        stubStatus = 200;
        hotelId = mongo.insert(new Hotel("pay-hotel", "Pay Hotel", "x", null, "Asia/Kolkata", "INR", Instant.now())).getId();
    }

    private void account(String linkedId) {
        mongo.insert(new Document("hotelId", hotelId).append("linkedAccountId", linkedId), "payoutAccounts");
    }

    private void earned(String paymentId, long gross, long fee) {
        ledger.recordOnlineCapture(hotelId, paymentId, "b", "s", "pay_" + paymentId, gross, fee, Instant.now());
    }

    @Test
    void theHotelsShareIsSentToItsLinkedAccount() {
        account("acc_hotelAAAA1");
        earned("p1", 10_000, 236); // commission 0 by default: the hotel's share is 9764

        PayoutTransfer t = payouts.schedule(hotelId, "p1", "pay_p1");

        assertThat(t.getStatus()).isEqualTo(TransferStatus.SENT);
        assertThat(t.getProviderTransferId()).startsWith("trf_test_");
        assertThat(t.getAmountMinor()).isEqualTo(9_764);
        assertThat(t.getLinkedAccountId()).isEqualTo("acc_hotelAAAA1");
        assertThat(PATHS).containsExactly("/v1/payments/pay_p1/transfers");
        assertThat(BODIES.get(0)).contains("\"account\":\"acc_hotelAAAA1\"").contains("\"amount\":9764")
                .contains("\"currency\":\"INR\"");
        assertThat(AUTH.get(0)).startsWith("Basic ");
    }

    @Test
    void scheduling_the_same_payment_twice_never_sends_twice() {
        account("acc_hotelAAAA1");
        earned("p1", 10_000, 236);

        PayoutTransfer first = payouts.schedule(hotelId, "p1", "pay_p1");
        PayoutTransfer second = payouts.schedule(hotelId, "p1", "pay_p1");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(PATHS).hasSize(1);
        assertThat(mongo.count(new Query(), PayoutTransfer.class)).isEqualTo(1);
    }

    @Test
    void withoutAPayoutAccountTheTransferWaitsAndIsSentOnceThereIsOne() {
        earned("p1", 10_000, 236);

        PayoutTransfer waiting = payouts.schedule(hotelId, "p1", "pay_p1");
        assertThat(waiting.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(PATHS).isEmpty();

        account("acc_hotelAAAA1");
        PayoutTransfer sent = payouts.sendOne(waiting.getId());

        assertThat(sent.getStatus()).isEqualTo(TransferStatus.SENT);
        assertThat(PATHS).hasSize(1);
    }

    @Test
    void aRefusalIsRecordedAsFailedAndNothingIsSentAgain() {
        account("acc_hotelAAAA1");
        earned("p1", 10_000, 236);
        stubStatus = 400;

        PayoutTransfer t = payouts.schedule(hotelId, "p1", "pay_p1");

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getNote()).contains("stub says no");
        payouts.sendOne(t.getId());
        assertThat(PATHS).hasSize(1); // FAILED is not retried automatically
    }

    @Test
    void ifRazorpayAnswersWithAServerErrorWeDoNotKnowAndAPersonChecks() {
        account("acc_hotelAAAA1");
        earned("p1", 10_000, 236);
        stubStatus = 500;

        PayoutTransfer t = payouts.schedule(hotelId, "p1", "pay_p1");

        assertThat(t.getStatus()).isEqualTo(TransferStatus.NEEDS_REVIEW);
        payouts.sendOne(t.getId());
        assertThat(PATHS).hasSize(1); // never sent a second time on its own
    }

    @Test
    void aShareBelowRazorpaysMinimumIsSkipped() {
        account("acc_hotelAAAA1");
        earned("p1", 150, 100); // the hotel's share is 50 paise

        PayoutTransfer t = payouts.schedule(hotelId, "p1", "pay_p1");

        assertThat(t.getStatus()).isEqualTo(TransferStatus.SKIPPED);
        assertThat(PATHS).isEmpty();
    }

    @Test
    void twoServersClaimingTheSameTransferSendItOnce() throws Exception {
        earned("p1", 10_000, 236);
        PayoutTransfer waiting = payouts.schedule(hotelId, "p1", "pay_p1"); // PENDING, no account yet
        account("acc_hotelAAAA1");

        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        var gate = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<PayoutTransfer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                gate.await();
                return payouts.sendOne(waiting.getId());
            }));
        }
        gate.countDown();
        for (var r : results) {
            r.get();
        }
        pool.shutdown();

        assertThat(PATHS).hasSize(1);
    }
}