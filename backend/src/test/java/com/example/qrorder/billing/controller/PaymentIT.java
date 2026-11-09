package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.model.PaymentStatus;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.SessionStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

class PaymentIT extends AbstractIntegrationTest {

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
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String sessionId;
    String orderId;
    String base;
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
        keyCounter = 0;
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(hotelId, "cashier2@example.com", "Cashier Dev", HotelRole.CASHIER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(otherHotelId, "otherwaiter@example.com", "Other Waiter", HotelRole.WAITER);
        acceptEverything(true);

        sessionId = sessions.save(new DiningSession(hotelId, "t-1", "T1", Instant.now())).getId();
        // 2 x 25000 + 5 x 10000 = 100000, delivered
        orderId = orders.save(new Order(hotelId, sessionId, "t-1", "T1", "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("i1", "Paneer Tikka", Diet.VEG, null, null, List.of(), 25000L, 2, 50000L, null),
                        new OrderLine("i2", "Thali", Diet.VEG, null, null, List.of(), 10000L, 5, 50000L, null)),
                100000L, null, null, Instant.now())).getId();
        setOrderStatus(OrderStatus.DELIVERED);
        base = "/api/v1/hotels/" + hotelId + "/sessions/" + sessionId;
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private void acceptEverything(boolean partialAllowed) {
        paymentSettings.deleteAll();
        paymentSettings.save(new PaymentSettings(hotelId,
                List.of(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.CARD), "shop@okbank", "Spice Garden",
                partialAllowed, "u", Instant.now()));
    }

    private void setOrderStatus(OrderStatus status) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)), new Update().set("status", status),
                Order.class);
    }

    private void allowCashiersToAdjust(boolean on) {
        Hotel hotel = hotels.findById(hotelId).orElseThrow();
        hotel.setCashiersMayAdjustBills(on);
        hotels.save(hotel);
    }

    private void closeSession() {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("status", SessionStatus.CLOSED).set("active", false), DiningSession.class);
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

    private String nextKey() {
        return "payment-key-%04d".formatted(++keyCounter);
    }

    private String body(String method, long amount) {
        return "{\"method\":\"%s\",\"amountMinor\":%d}".formatted(method, amount);
    }

    private ResultActions pay(String email, String method, long amount) throws Exception {
        return call(post(base + "/payments").header("Idempotency-Key", nextKey()), email, body(method, amount));
    }

    private ResultActions payWithKey(String email, String key, String json) throws Exception {
        return call(post(base + "/payments").header("Idempotency-Key", key), email, json);
    }

    private ResultActions bill(String email) throws Exception {
        return call(get(base + "/bill"), email, null);
    }

    private String idOf(ResultActions recorded) throws Exception {
        return JsonPath.read(recorded.andReturn().getResponse().getContentAsString(), "$.payment.id");
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long paidOnBill() {
        return bills.findByDiningSessionId(sessionId).orElseThrow().getPaidMinor();
    }

    // ---------- recording ----------

    @Test
    void aCashierRecordsAPartPaymentAndTheBillShowsWhatIsLeft() throws Exception {
        pay("cashier@example.com", "CASH", 40000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.method").value("CASH"))
                .andExpect(jsonPath("$.payment.amountMinor").value(40000))
                .andExpect(jsonPath("$.payment.status").value("RECORDED"))
                .andExpect(jsonPath("$.payment.receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.totalMinor").value(100000))
                .andExpect(jsonPath("$.paidMinor").value(40000))
                .andExpect(jsonPath("$.balanceMinor").value(60000))
                .andExpect(jsonPath("$.overpaidMinor").value(0));

        bill("cashier@example.com")
                .andExpect(jsonPath("$.paidMinor").value(40000))
                .andExpect(jsonPath("$.balanceMinor").value(60000));
        call(get(base + "/payments"), "w1@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amountMinor").value(40000));
    }

    @Test
    void severalPaymentsSettleTheBillAndNothingMoreIsAccepted() throws Exception {
        call(post(base + "/payments").header("Idempotency-Key", nextKey()), "cashier@example.com",
                "{\"method\":\"UPI\",\"amountMinor\":40000,\"reference\":\"UTR123456\",\"payerLabel\":\"Guest 1\","
                        + "\"note\":\"  paid by phone \"}")
                .andExpect(status().isCreated());
        pay("manager@example.com", "CARD", 60000)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceMinor").value(0));

        pay("cashier@example.com", "CASH", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("That is more than the amount due (0.00 INR)"));

        call(get(base + "/payments"), "cashier@example.com", null)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].method").value("UPI"))
                .andExpect(jsonPath("$[0].reference").value("UTR123456"))
                .andExpect(jsonPath("$[0].payerLabel").value("Guest 1"))
                .andExpect(jsonPath("$[0].note").value("paid by phone"))
                .andExpect(jsonPath("$[1].method").value("CARD"));
        assertThat(paidOnBill()).isEqualTo(100000);
    }

    @Test
    void aPaymentNeverExceedsWhatIsDueAndMustBePositive() throws Exception {
        pay("cashier@example.com", "CASH", 100001)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That is more than the amount due (1000.00 INR)"));
        pay("cashier@example.com", "CASH", 0).andExpect(status().isBadRequest());
        pay("cashier@example.com", "CASH", -5).andExpect(status().isBadRequest());
        call(post(base + "/payments").header("Idempotency-Key", nextKey()), "cashier@example.com",
                "{\"amountMinor\":100}").andExpect(status().isBadRequest()); // no method
        call(post(base + "/payments").header("Idempotency-Key", nextKey()), "cashier@example.com",
                "{\"method\":\"CASH\",\"amountMinor\":100,\"payerLabel\":\"" + "x".repeat(31) + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(payments.count()).isZero();
        assertThat(paidOnBill()).isZero();
    }

    @Test
    void onlyMethodsTheHotelAcceptsCanBeRecorded() throws Exception {
        paymentSettings.deleteAll(); // no settings at all: cash only

        pay("cashier@example.com", "UPI", 1000)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("This hotel does not accept UPI payments"));
        pay("cashier@example.com", "CARD", 1000).andExpect(status().isBadRequest());
        pay("cashier@example.com", "CASH", 1000).andExpect(status().isCreated());
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void aHotelThatDoesNotAllowPartPaymentsTakesTheFullAmountOnly() throws Exception {
        acceptEverything(false);

        pay("cashier@example.com", "CASH", 40000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("This hotel only takes the full amount due (1000.00 INR) in one payment"));
        assertThat(payments.count()).isZero();

        pay("cashier@example.com", "CASH", 100000)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceMinor").value(0));
    }

    // ---------- idempotency ----------

    @Test
    void repeatingTheSameRequestReturnsTheSamePaymentAndRecordsNothingNew() throws Exception {
        String key = nextKey();
        String json = body("CASH", 40000);

        String first = idOf(payWithKey("cashier@example.com", key, json).andExpect(status().isCreated()));
        payWithKey("cashier@example.com", key, json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(first))
                .andExpect(jsonPath("$.paidMinor").value(40000));
        // another cashier retrying with the same key (a shared tablet) gets the same answer
        payWithKey("cashier2@example.com", key, json).andExpect(status().isOk());

        assertThat(payments.count()).isEqualTo(1);
        assertThat(paidOnBill()).isEqualTo(40000);

        payWithKey("cashier@example.com", key, body("CASH", 50000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This Idempotency-Key was already used for a different payment"));
    }

    @Test
    void theIdempotencyKeyIsRequiredAndMustLookRight() throws Exception {
        call(post(base + "/payments"), "cashier@example.com", body("CASH", 100))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        payWithKey("cashier@example.com", "short", body("CASH", 100)).andExpect(status().isBadRequest());
        payWithKey("cashier@example.com", "has spaces in it, not allowed", body("CASH", 100))
                .andExpect(status().isBadRequest());
        payWithKey("cashier@example.com", "x".repeat(65), body("CASH", 100)).andExpect(status().isBadRequest());
        assertThat(payments.count()).isZero();
    }

    // ---------- concurrency ----------

    @Test
    void simultaneousPaymentsCanNeverAddUpToMoreThanTheBill() throws Exception {
        String cashier = login("cashier@example.com");
        String cashier2 = login("cashier2@example.com");
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String key = nextKey();
            String token = i % 2 == 0 ? cashier : cashier2;
            tasks.add(() -> mvc.perform(post(base + "/payments").header("Idempotency-Key", key)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body("CASH", 20000)))
                    .andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runTogether(tasks);

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5); // 5 x 20000 = 100000
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1);
        assertThat(paidOnBill()).isEqualTo(100000);
        assertThat(payments.count()).isEqualTo(5);
    }

    @Test
    void theSameRequestSentTwiceAtOnceIsRecordedOnce() throws Exception {
        String token = login("cashier@example.com");
        String key = nextKey();
        List<Callable<Integer>> tasks = List.of(
                () -> mvc.perform(post(base + "/payments").header("Idempotency-Key", key)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON).content(body("CASH", 40000)))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(post(base + "/payments").header("Idempotency-Key", key)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON).content(body("CASH", 40000)))
                        .andReturn().getResponse().getStatus());

        List<Integer> statuses = runTogether(tasks);

        assertThat(statuses).containsExactlyInAnyOrder(200, 201);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(paidOnBill()).isEqualTo(40000);
    }

    // ---------- voiding ----------

    @Test
    void aManagerVoidsAWrongPaymentWhichStopsCountingButStaysInTheLedger() throws Exception {
        String id = idOf(pay("cashier@example.com", "CASH", 40000).andExpect(status().isCreated()));
        String voidUrl = base + "/payments/" + id + "/void";

        call(post(voidUrl), "manager@example.com", "{\"reason\":\"ab\"}").andExpect(status().isBadRequest());
        call(post(voidUrl), "manager@example.com", "{\"reason\":\"Entered by mistake\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("VOIDED"))
                .andExpect(jsonPath("$.payment.voidReason").value("Entered by mistake"))
                .andExpect(jsonPath("$.payment.voidedByUserId").isNotEmpty())
                .andExpect(jsonPath("$.paidMinor").value(0))
                .andExpect(jsonPath("$.balanceMinor").value(100000));
        call(post(voidUrl), "manager@example.com", "{\"reason\":\"Entered by mistake\"}")
                .andExpect(status().isOk()); // repeating it changes nothing

        assertThat(paidOnBill()).isZero();
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.VOIDED);
        call(get(base + "/payments"), "cashier@example.com", null)
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("VOIDED"));

        pay("cashier@example.com", "CASH", 100000).andExpect(status().isCreated()); // the full amount fits again
    }

    @Test
    void cashiersMayVoidOnlyWhenTheOwnerAllowsItAndNobodyElseEver() throws Exception {
        String id = idOf(pay("cashier@example.com", "CASH", 40000).andExpect(status().isCreated()));
        String voidUrl = base + "/payments/" + id + "/void";
        String json = "{\"reason\":\"Wrong amount\"}";

        allowCashiersToAdjust(false);
        call(post(voidUrl), "cashier@example.com", json).andExpect(status().isForbidden());
        call(post(voidUrl), "w1@example.com", json).andExpect(status().isForbidden());
        call(post(voidUrl), "cook@example.com", json).andExpect(status().isForbidden());
        call(post(voidUrl), null, json).andExpect(status().isUnauthorized());
        assertThat(paidOnBill()).isEqualTo(40000);

        allowCashiersToAdjust(true);
        call(post(voidUrl), "cashier@example.com", json).andExpect(status().isOk());
        assertThat(paidOnBill()).isZero();

        call(post(base + "/payments/no-such-payment/void"), "manager@example.com", json)
                .andExpect(status().isNotFound());
    }

    // ---------- access and tables ----------

    @Test
    void whoMayRecordAndWhoMayLook() throws Exception {
        pay("w1@example.com", "CASH", 1000).andExpect(status().isForbidden());
        pay("cook@example.com", "CASH", 1000).andExpect(status().isForbidden());
        pay(null, "CASH", 1000).andExpect(status().isUnauthorized());
        pay("otherwaiter@example.com", "CASH", 1000).andExpect(status().isNotFound()); // not a member here
        pay("manager@example.com", "CASH", 1000).andExpect(status().isCreated());

        call(get(base + "/payments"), "w1@example.com", null).andExpect(status().isOk());
        call(get(base + "/payments"), "cook@example.com", null).andExpect(status().isForbidden());
        call(get(base + "/payments"), null, null).andExpect(status().isUnauthorized());

        String foreign = sessions.save(new DiningSession(otherHotelId, "t-9", "T9", Instant.now())).getId();
        String root = "/api/v1/hotels/" + hotelId + "/sessions/";
        call(post(root + "no-such-session/payments").header("Idempotency-Key", nextKey()), "cashier@example.com",
                body("CASH", 100)).andExpect(status().isNotFound());
        call(post(root + foreign + "/payments").header("Idempotency-Key", nextKey()), "cashier@example.com",
                body("CASH", 100)).andExpect(status().isNotFound());
        call(get(root + foreign + "/payments"), "cashier@example.com", null).andExpect(status().isNotFound());
    }

    @Test
    void aClearedTableTakesNoMorePaymentsButAnEarlierRetryStillAnswers() throws Exception {
        String key = nextKey();
        String json = body("CASH", 40000);
        String id = idOf(payWithKey("cashier@example.com", key, json).andExpect(status().isCreated()));
        closeSession();

        payWithKey("cashier@example.com", key, json)
                .andExpect(status().isOk()).andExpect(jsonPath("$.payment.id").value(id)); // the retry still works
        pay("cashier@example.com", "CASH", 1000).andExpect(status().isConflict());
        call(post(base + "/payments/" + id + "/void"), "manager@example.com", "{\"reason\":\"Too late now\"}")
                .andExpect(status().isConflict());
        assertThat(paidOnBill()).isEqualTo(40000);
    }

    // ---------- the bill follows the money ----------

    @Test
    void aDiscountCannotBringTheBillBelowWhatWasAlreadyPaid() throws Exception {
        pay("cashier@example.com", "CASH", 90000).andExpect(status().isCreated());

        call(post(base + "/bill/discounts"), "manager@example.com",
                "{\"type\":\"FLAT\",\"value\":20000,\"reason\":\"Too generous\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This discount would bring the bill below what has already been paid"));
        call(post(base + "/bill/discounts"), "manager@example.com",
                "{\"type\":\"FLAT\",\"value\":5000,\"reason\":\"Fine amount\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalMinor").value(95000))
                .andExpect(jsonPath("$.paidMinor").value(90000))
                .andExpect(jsonPath("$.balanceMinor").value(5000));
    }

    @Test
    void ifTheTotalFallsBelowWhatWasPaidTheBillShowsARefundDue() throws Exception {
        pay("cashier@example.com", "CASH", 100000).andExpect(status().isCreated());

        setOrderStatus(OrderStatus.CANCELLED); // the delivered order is cancelled afterwards

        bill("cashier@example.com")
                .andExpect(jsonPath("$.totalMinor").value(0))
                .andExpect(jsonPath("$.paidMinor").value(100000))
                .andExpect(jsonPath("$.balanceMinor").value(0))
                .andExpect(jsonPath("$.overpaidMinor").value(100000));
        pay("cashier@example.com", "CASH", 100).andExpect(status().isConflict());
    }
}