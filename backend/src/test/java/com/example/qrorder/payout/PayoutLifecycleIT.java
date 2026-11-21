package com.example.qrorder.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.earnings.service.EarningsLedger;
import com.example.qrorder.payout.gateway.RouteGateway;
import com.example.qrorder.payout.model.HotelPayoutAccount;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import com.example.qrorder.payout.service.PayoutService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.payments.platform.razorpay.key-id=rzp_test_platform",
        "app.payments.platform.razorpay.key-secret=platform-secret-123456",
        "app.payments.platform.razorpay.webhook-secret=platform-whsec-123456"})
class PayoutLifecycleIT extends AbstractIntegrationTest {

    private static final String WHSEC = "platform-whsec-123456";

    @MockitoBean RouteGateway gateway;
    @Autowired MockMvc mvc;
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
        hotelId = mongo.insert(new Hotel("life-hotel", "Life Hotel", "x", null, "Asia/Kolkata", "INR", Instant.now())).getId();
    }

    /** A transfer in the given state for payment p1 (the hotel's share: 9764). */
    private PayoutTransfer transfer(String paymentId, TransferStatus status) {
        PayoutTransfer t = mongo.insert(new PayoutTransfer(hotelId, paymentId, "pay_" + paymentId, 9_764, "INR", status,
                null, Instant.now()));
        if (status == TransferStatus.SENT || status == TransferStatus.PROCESSED) {
            mongo.updateFirst(Query.query(Criteria.where("id").is(t.getId())),
                    new Update().set("providerTransferId", "trf_" + paymentId), PayoutTransfer.class);
        }
        return t;
    }

    private PayoutTransfer reload(String id) {
        return mongo.findById(id, PayoutTransfer.class);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WHSEC.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions transferEvent(String event, String transferRef, String providerId, long amount, String error)
            throws Exception {
        String body = """
                {"event":"%s","payload":{"transfer":{"entity":{"id":"%s","status":"x","amount":%d,"currency":"INR",
                "notes":{"reference":"%s"},"error":%s}}}}""".formatted(event, providerId, amount, transferRef, error);
        return mvc.perform(post("/api/v1/webhooks/razorpay-platform").contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", sign(body)).content(body));
    }

    @Test
    void aProcessedTransferIsMarkedAndTheHotelsLedgerShowsThePayout() throws Exception {
        ledger.recordOnlineCapture(hotelId, "p1", "b", "s", "pay_p1", 10_000, 236L, Instant.now());
        PayoutTransfer t = transfer("p1", TransferStatus.SENT);

        transferEvent("transfer.processed", t.getId(), "trf_p1", 9_764, "null").andExpect(status().isOk());
        transferEvent("transfer.processed", t.getId(), "trf_p1", 9_764, "null").andExpect(status().isOk()); // a replay

        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.PROCESSED);
        List<EarningsEntry> payoutLines = entries.findAll().stream().filter(e -> e.getType() == EarningsEntryType.PAYOUT).toList();
        assertThat(payoutLines).hasSize(1);
        assertThat(payoutLines.get(0).getNetMinor()).isEqualTo(-9_764);
        assertThat(entries.findAll().stream().mapToLong(EarningsEntry::getNetMinor).sum()).isZero(); // owed minus paid
    }

    @Test
    void aWebhookThatArrivesBeforeWeSavedTheTransferIdStillFindsItByOurReference() throws Exception {
        ledger.recordOnlineCapture(hotelId, "p1", "b", "s", "pay_p1", 10_000, 236L, Instant.now());
        PayoutTransfer t = transfer("p1", TransferStatus.SENDING); // no providerTransferId yet

        transferEvent("transfer.processed", t.getId(), "trf_early", 9_764, "null").andExpect(status().isOk());

        PayoutTransfer after = reload(t.getId());
        assertThat(after.getStatus()).isEqualTo(TransferStatus.PROCESSED);
        assertThat(after.getProviderTransferId()).isEqualTo("trf_early");
    }

    @Test
    void aFailedTransferShowsWhyAndALateFailureNeverUndoesAProcessedOne() throws Exception {
        PayoutTransfer failing = transfer("p1", TransferStatus.SENT);
        transferEvent("transfer.failed", failing.getId(), "trf_p1", 9_764,
                "{\"description\":\"insufficient balance\"}").andExpect(status().isOk());
        assertThat(reload(failing.getId()).getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(reload(failing.getId()).getNote()).contains("insufficient balance");

        PayoutTransfer done = transfer("p2", TransferStatus.PROCESSED);
        transferEvent("transfer.failed", done.getId(), "trf_p2", 9_764, "null").andExpect(status().isOk());
        assertThat(reload(done.getId()).getStatus()).isEqualTo(TransferStatus.PROCESSED);
    }

    @Test
    void aDifferentAmountFromRazorpayNeedsAPerson() throws Exception {
        PayoutTransfer t = transfer("p1", TransferStatus.SENT);

        transferEvent("transfer.processed", t.getId(), "trf_p1", 5_000, "null").andExpect(status().isOk());

        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.NEEDS_REVIEW);
        assertThat(entries.count()).isZero(); // nothing written to the ledger
    }

    @Test
    void aRefundCancelsAnUnsentTransferAndFlagsASentOne() {
        PayoutTransfer unsent = transfer("p1", TransferStatus.PENDING);
        PayoutTransfer sent = transfer("p2", TransferStatus.PROCESSED);

        payouts.markPaymentRefunded("p1");
        payouts.markPaymentRefunded("p2");

        assertThat(reload(unsent.getId()).getStatus()).isEqualTo(TransferStatus.SKIPPED);
        assertThat(reload(unsent.getId()).isReversalDue()).isFalse();
        assertThat(reload(sent.getId()).getStatus()).isEqualTo(TransferStatus.PROCESSED);
        assertThat(reload(sent.getId()).isReversalDue()).isTrue();
    }

    @Test
    void theSweeperFlagsAnAbandonedClaimAndSendsWhatIsWaiting() {
        PayoutTransfer abandoned = transfer("p1", TransferStatus.SENDING);
        mongo.updateFirst(Query.query(Criteria.where("id").is(abandoned.getId())),
                new Update().set("lockedUntil", Instant.now().minusSeconds(300)), PayoutTransfer.class);
        PayoutTransfer waiting = transfer("p2", TransferStatus.PENDING);
        mongo.insert(new Document("hotelId", hotelId).append("linkedAccountId", "acc_hotelAAAA1"), "payoutAccounts");
        when(gateway.transfer(any(), anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new RouteGateway.Result("trf_new", "created"));

        payouts.sweep();

        assertThat(reload(abandoned.getId()).getStatus()).isEqualTo(TransferStatus.NEEDS_REVIEW);
        assertThat(reload(waiting.getId()).getStatus()).isEqualTo(TransferStatus.SENT);
        verify(gateway, never()).transfer(any(), org.mockito.ArgumentMatchers.eq("pay_p1"), anyString(), anyLong(),
                anyString(), anyString()); // the abandoned one was NOT sent again
    }

    @Test
    void aWebhookWithABadSignatureChangesNothing() throws Exception {
        PayoutTransfer t = transfer("p1", TransferStatus.SENT);
        String body = "{\"event\":\"transfer.processed\",\"payload\":{\"transfer\":{\"entity\":{\"id\":\"trf_p1\",\"amount\":9764,"
                + "\"notes\":{\"reference\":\"" + t.getId() + "\"}}}}}";

        mvc.perform(post("/api/v1/webhooks/razorpay-platform").contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", "deadbeef").content(body)).andExpect(status().isUnauthorized());

        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.SENT);
    }
}