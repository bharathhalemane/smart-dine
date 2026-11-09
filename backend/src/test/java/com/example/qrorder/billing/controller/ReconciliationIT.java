package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.OnlinePaymentRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ReconciliationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningSessionRepository sessions;
    @Autowired OrderRepository orders;
    @Autowired BillRepository bills;
    @Autowired PaymentRepository payments;
    @Autowired PaymentSettingsRepository paymentSettings;
    @Autowired OnlinePaymentRepository attempts;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String sessionId;
    String base;
    String url;
    int keyCounter;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        sessions.deleteAll();
        orders.deleteAll();
        bills.deleteAll();
        payments.deleteAll();
        paymentSettings.deleteAll();
        attempts.deleteAll();
        mongo.dropCollection("reconciliationLog");
        keyCounter = 0;
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member("manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member("cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member("w1@example.com", "Waiter One", HotelRole.WAITER);
        paymentSettings.save(new PaymentSettings(hotelId, List.of(PaymentMethod.CASH), null, null, true, "u",
                Instant.now()));

        sessionId = sessions.save(new DiningSession(hotelId, "t-1", "T1", Instant.now())).getId();
        String orderId = orders.save(new Order(hotelId, sessionId, "t-1", "T1", "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("i1", "Thali", Diet.VEG, null, null, List.of(), 50000L, 2, 100000L, null)),
                100000L, null, null, Instant.now())).getId();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)),
                new Update().set("status", OrderStatus.DELIVERED), Order.class);
        base = "/api/v1/hotels/" + hotelId + "/sessions/" + sessionId;
        url = "/api/v1/hotels/" + hotelId + "/reconciliation";
    }

    // ---------- helpers ----------

    private void member(String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private void payCash(long amount) throws Exception {
        call(post(base + "/payments").header("Idempotency-Key", "reconcile-key-%04d".formatted(++keyCounter)),
                "cashier@example.com", "{\"method\":\"CASH\",\"amountMinor\":%d}".formatted(amount))
                .andExpect(status().isCreated());
    }

    private String billId() {
        return bills.findByDiningSessionId(sessionId).orElseThrow().getId();
    }

    private void setPaid(long paid) {
        mongo.updateFirst(new Query(), new Update().set("paidMinor", paid), Bill.class);
    }

    private long paid() {
        return bills.findByDiningSessionId(sessionId).orElseThrow().getPaidMinor();
    }

    private void makeBillExist() throws Exception {
        call(get(base + "/bill"), "cashier@example.com", null).andExpect(status().isOk());
    }

    // ---------- the report ----------

    @Test
    void aBillThatAgreesWithItsLedgerIsClean() throws Exception {
        payCash(40000);

        call(get(url), "manager@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billsChecked").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.billDrifts.length()").value(0))
                .andExpect(jsonPath("$.settlementGaps.length()").value(0))
                .andExpect(jsonPath("$.onlineMismatches.length()").value(0))
                .andExpect(jsonPath("$.staleOnlineAttempts").value(0))
                .andExpect(jsonPath("$.needsReviewCount").value(0));
    }

    @Test
    void aBillThatClaimsMoreOrLessThanTheLedgerIsReportedWithTheDifference() throws Exception {
        payCash(40000);

        setPaid(99999);
        call(get(url), "manager@example.com", null)
                .andExpect(jsonPath("$.billDrifts.length()").value(1))
                .andExpect(jsonPath("$.billDrifts[0].paidOnBillMinor").value(99999))
                .andExpect(jsonPath("$.billDrifts[0].ledgerMinor").value(40000))
                .andExpect(jsonPath("$.billDrifts[0].differenceMinor").value(59999))
                .andExpect(jsonPath("$.billDrifts[0].tableNumber").value("T1"));

        setPaid(0);
        call(get(url), "manager@example.com", null)
                .andExpect(jsonPath("$.billDrifts[0].differenceMinor").value(-40000));
    }

    @Test
    void aVoidedPaymentNoLongerCountsInTheLedger() throws Exception {
        payCash(40000);
        String paymentId = payments.findAll().get(0).getId();
        call(post(base + "/payments/" + paymentId + "/void"), "manager@example.com", "{\"reason\":\"Entered twice\"}")
                .andExpect(status().isOk());

        call(get(url), "manager@example.com", null).andExpect(jsonPath("$.billDrifts.length()").value(0));
    }

    @Test
    void aClearedTableThatLeftOwingIsListedAsASettlementGap() throws Exception {
        makeBillExist();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("active", false), DiningSession.class);

        call(get(url), "manager@example.com", null)
                .andExpect(jsonPath("$.settlementGaps.length()").value(1))
                .andExpect(jsonPath("$.settlementGaps[0].totalMinor").value(100000))
                .andExpect(jsonPath("$.settlementGaps[0].paidMinor").value(0))
                .andExpect(jsonPath("$.settlementGaps[0].owedMinor").value(100000))
                .andExpect(jsonPath("$.settlementGaps[0].overpaidMinor").value(0));

        payCashAfterClearViaLedger(100000);
        call(get(url), "manager@example.com", null).andExpect(jsonPath("$.settlementGaps.length()").value(0));
    }

    /** Settles the closed bill the way the data would look after a normal payment. */
    private void payCashAfterClearViaLedger(long amount) {
        Bill bill = bills.findByDiningSessionId(sessionId).orElseThrow();
        payments.save(new Payment(hotelId, sessionId, bill.getId(), PaymentMethod.CASH, amount, null, null, null,
                "u", Instant.now(), "settled-key-00000001", "h"));
        setPaid(amount);
    }

    @Test
    void onlineMoneyAndTheLedgerMustAgree() throws Exception {
        makeBillExist();
        String billId = billId();
        Instant now = Instant.now();

        // the provider says it was paid, but there is no ledger entry
        OnlinePayment orphanCapture = attempts.save(new OnlinePayment("aaaaaaaaaaaaaaaaaaaaaaaa", hotelId, sessionId,
                "T1", "cust-1", "Guest 1", 5000, "INR", "razorpay", "order_x1", "k-0000000000000001", "h", now));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orphanCapture.getId())),
                new Update().set("status", OnlinePaymentStatus.CAPTURED).set("providerPaymentId", "pay_x1"),
                OnlinePayment.class);

        // a ledger entry (an hour old) with no captured attempt behind it
        payments.save(new Payment(hotelId, sessionId, billId, PaymentMethod.ONLINE, 7000, "pay_x2", null, null,
                "online:razorpay", now.minus(1, ChronoUnit.HOURS), "online-bbbbbbbbbbbbbbbbbbbbbbbb", "online"));
        // and a fresh one that may still be finishing: not reported yet
        payments.save(new Payment(hotelId, sessionId, billId, PaymentMethod.ONLINE, 3000, "pay_x3", null, null,
                "online:razorpay", now.minus(1, ChronoUnit.MINUTES), "online-cccccccccccccccccccccccc", "online"));
        setPaid(10000); // keep the counter honest so only the online checks speak

        call(get(url), "manager@example.com", null)
                .andExpect(jsonPath("$.billDrifts.length()").value(0))
                .andExpect(jsonPath("$.onlineMismatches.length()").value(2))
                .andExpect(jsonPath("$.onlineMismatches[?(@.kind=='CAPTURED_WITHOUT_PAYMENT')].amountMinor")
                        .value(5000))
                .andExpect(jsonPath("$.onlineMismatches[?(@.kind=='PAYMENT_WITHOUT_CAPTURE')].amountMinor")
                        .value(7000));
    }

    @Test
    void theWindowLimitsWhichBillsAreChecked() throws Exception {
        payCash(40000);
        Instant long_ago = Instant.now().minus(400, ChronoUnit.DAYS);
        mongo.updateFirst(new Query(), new Update().set("createdAt", long_ago).set("updatedAt", long_ago), Bill.class);
        setPaid(1); // a drift that is outside the window

        call(get(url + "?days=7"), "manager@example.com", null)
                .andExpect(jsonPath("$.billsChecked").value(0)).andExpect(jsonPath("$.billDrifts.length()").value(0));
        call(get(url + "?days=366"), "manager@example.com", null).andExpect(jsonPath("$.billsChecked").value(0));
        call(get(url + "?days=0"), "manager@example.com", null).andExpect(status().isBadRequest());
        call(get(url + "?days=367"), "manager@example.com", null).andExpect(status().isBadRequest());
    }

    // ---------- repair ----------

    @Test
    void repairMovesTheCounterToTheLedgerAndLogsIt() throws Exception {
        payCash(40000);
        setPaid(99999);
        String repairUrl = url + "/bills/" + billId() + "/repair";

        call(post(repairUrl), "manager@example.com", "{\"reason\":\"ab\"}").andExpect(status().isBadRequest());
        call(post(repairUrl), "manager@example.com", "{\"reason\":\"Counter was edited by hand\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.beforeMinor").value(99999))
                .andExpect(jsonPath("$.afterMinor").value(40000));
        assertThat(paid()).isEqualTo(40000);

        call(post(repairUrl), "manager@example.com", "{\"reason\":\"Counter was edited by hand\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false)); // nothing left to fix

        call(get(url + "/repairs"), "manager@example.com", null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].beforeMinor").value(99999))
                .andExpect(jsonPath("$[0].afterMinor").value(40000))
                .andExpect(jsonPath("$[0].reason").value("Counter was edited by hand"))
                .andExpect(jsonPath("$[0].byUserId").isNotEmpty());
        call(get(url), "manager@example.com", null).andExpect(jsonPath("$.billDrifts.length()").value(0));
    }

    @Test
    void repairWorksWhenTheCounterWasLostEntirely() throws Exception {
        payCash(40000);
        setPaid(0);

        call(post(url + "/bills/" + billId() + "/repair"), "manager@example.com", "{\"reason\":\"Counter was reset\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.afterMinor").value(40000));
        assertThat(paid()).isEqualTo(40000);
    }

    @Test
    void repairOfAnUnknownBillIsNotFound() throws Exception {
        call(post(url + "/bills/no-such-bill/repair"), "manager@example.com", "{\"reason\":\"Checking\"}")
                .andExpect(status().isNotFound());
    }

    // ---------- abandoned online attempts ----------

    @Test
    void onlyOldUnpaidAttemptsAreExpired() throws Exception {
        Instant now = Instant.now();
        attempts.save(new OnlinePayment("aaaaaaaaaaaaaaaaaaaaaaa1", hotelId, sessionId, "T1", "cust-1", "Guest 1",
                5000, "INR", "razorpay", "order_old", "k-0000000000000001", "h", now.minus(30, ChronoUnit.HOURS)));
        attempts.save(new OnlinePayment("aaaaaaaaaaaaaaaaaaaaaaa2", hotelId, sessionId, "T1", "cust-1", "Guest 1",
                5000, "INR", "razorpay", "order_new", "k-0000000000000002", "h", now.minus(1, ChronoUnit.HOURS)));

        call(get(url), "manager@example.com", null).andExpect(jsonPath("$.staleOnlineAttempts").value(1));
        call(post(url + "/online-attempts/expire"), "manager@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.expired").value(1));

        assertThat(attempts.findById("aaaaaaaaaaaaaaaaaaaaaaa1").orElseThrow().getStatus())
                .isEqualTo(OnlinePaymentStatus.EXPIRED);
        assertThat(attempts.findById("aaaaaaaaaaaaaaaaaaaaaaa2").orElseThrow().getStatus())
                .isEqualTo(OnlinePaymentStatus.CREATED);
        call(post(url + "/online-attempts/expire"), "manager@example.com", null)
                .andExpect(jsonPath("$.expired").value(0));
        call(post(url + "/online-attempts/expire?hours=0"), "manager@example.com", null)
                .andExpect(status().isBadRequest());
        call(post(url + "/online-attempts/expire?hours=1"), "manager@example.com", null)
                .andExpect(jsonPath("$.expired").value(1)); // the 1-hour-old one is now past the cutoff
    }

    // ---------- access ----------

    @Test
    void onlyThoseWhoMayAdjustBillsCanReconcile() throws Exception {
        makeBillExist();
        String repairUrl = url + "/bills/" + billId() + "/repair";
        String reason = "{\"reason\":\"Checking it\"}";

        call(get(url), "cashier@example.com", null).andExpect(status().isForbidden());
        call(get(url), "w1@example.com", null).andExpect(status().isForbidden());
        call(get(url), null, null).andExpect(status().isUnauthorized());
        call(post(repairUrl), "cashier@example.com", reason).andExpect(status().isForbidden());
        call(get(url + "/repairs"), "cashier@example.com", null).andExpect(status().isForbidden());
        call(post(url + "/online-attempts/expire"), "cashier@example.com", null).andExpect(status().isForbidden());
    }
}