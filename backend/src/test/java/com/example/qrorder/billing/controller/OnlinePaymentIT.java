package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.Bill;
import com.example.qrorder.billing.model.HotelPaymentProvider;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import com.example.qrorder.billing.model.Payment;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.HotelPaymentProviderRepository;
import com.example.qrorder.billing.repository.OnlinePaymentRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OnlinePaymentIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String KEY_ID = "rzp_test_abc123XYZ";
    private static final String KEY_SECRET = "key-secret-123456";
    private static final String WEBHOOK_SECRET = "whsec-test-123456";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    // ---- a stand-in for Razorpay's order API ----
    static final List<String> STUB_AUTH = new CopyOnWriteArrayList<>();
    static final List<String> STUB_BODIES = new CopyOnWriteArrayList<>();
    static final AtomicInteger STUB_ORDERS = new AtomicInteger();
    static volatile int stubStatus = 200;
    static final HttpServer RAZORPAY = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/orders", exchange -> {
                STUB_AUTH.add(exchange.getRequestHeaders().getFirst("Authorization"));
                STUB_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] reply;
                int code = stubStatus;
                if (code == 200) {
                    reply = ("{\"id\":\"order_test_" + STUB_ORDERS.incrementAndGet() + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    reply = "{}".getBytes(StandardCharsets.UTF_8);
                }
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

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;
    @Autowired MenuCategoryRepository categories;
    @Autowired MenuItemRepository items;
    @Autowired OrderRepository orders;
    @Autowired BillRepository bills;
    @Autowired PaymentRepository payments;
    @Autowired PaymentSettingsRepository paymentSettings;
    @Autowired HotelPaymentProviderRepository providerConfigs;
    @Autowired OnlinePaymentRepository attempts;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String sessionId;
    String anita;
    String hookPath;
    int keyCounter;

    @BeforeEach
    void seed() throws Exception {
        stubStatus = 200;
        STUB_AUTH.clear();
        STUB_BODIES.clear();
        STUB_ORDERS.set(0);
        keyCounter = 0;
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        categories.deleteAll();
        items.deleteAll();
        orders.deleteAll();
        bills.deleteAll();
        payments.deleteAll();
        paymentSettings.deleteAll();
        providerConfigs.deleteAll();
        attempts.deleteAll();
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now)).getId();
        member("owner@example.com", "Owner Om", HotelRole.OWNER);
        member("manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member("cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-t1"), "u", now));
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        String soup = items.save(new MenuItem(hotelId, category, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        String lassi = items.save(new MenuItem(hotelId, category, "Lassi", null, 6000L, Diet.VEG, 1, now)).getId();

        anita = newDiner();
        place(anita, soup, 1);
        place(anita, lassi, 2); // 15000 + 12000 = 27000
        mongo.updateMulti(new Query(), new Update().set("status", OrderStatus.DELIVERED), Order.class);
        sessionId = sessions.findAll().get(0).getId();

        paymentSettings.save(new PaymentSettings(hotelId, List.of(PaymentMethod.CASH, PaymentMethod.ONLINE), null,
                null, true, "u", now));
        hookPath = connectProvider();
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

    private String connectProvider() throws Exception {
        MvcResult result = call(put("/api/v1/hotels/" + hotelId + "/payments/provider"), "owner@example.com",
                "{\"keyId\":\"%s\",\"keySecret\":\"%s\",\"webhookSecret\":\"%s\"}"
                        .formatted(KEY_ID, KEY_SECRET, WEBHOOK_SECRET))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.webhookPath");
    }

    private String newDiner() throws Exception {
        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-t1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void place(String cookie, String itemId, int quantity) throws Exception {
        mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":%d}]}".formatted(itemId, quantity))
                        .cookie(new Cookie("qr_customer", cookie)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
    }

    private String nextKey() {
        return "online-start-key-%04d".formatted(++keyCounter);
    }

    private ResultActions start(String cookie, String key, String json) throws Exception {
        var request = post("/api/v1/customer/payments/online").contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (cookie != null) {
            request.cookie(new Cookie("qr_customer", cookie));
        }
        return mvc.perform(request);
    }

    private String startOk(long amountMinor) throws Exception {
        MvcResult result = start(anita, nextKey(), "{\"amountMinor\":%d}".formatted(amountMinor))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.attemptId");
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String event(String name, String orderId, String paymentId, long amount, String currency) {
        return """
                {"event":"%s","payload":{"payment":{"entity":{"id":"%s","order_id":"%s","amount":%d,"currency":"%s"}}}}"""
                .formatted(name, paymentId, orderId, amount, currency);
    }

    private ResultActions webhook(String path, String body, String signature) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }
        return mvc.perform(request);
    }

    private ResultActions signedWebhook(String body) throws Exception {
        return webhook(hookPath, body, sign(body, WEBHOOK_SECRET));
    }

    private String orderIdOf(String attemptId) {
        return attempts.findById(attemptId).orElseThrow().getProviderOrderId();
    }

    private long paidOnBill() {
        return bills.findByDiningSessionId(sessionId).map(Bill::getPaidMinor).orElse(0L);
    }

    // ---------- the hotel connects its provider ----------

    @Test
    void theOwnerConnectsTheProviderAndSecretsAreNeverShownOrStoredInTheClear() throws Exception {
        String url = "/api/v1/hotels/" + hotelId + "/payments/provider";

        call(get(url), "owner@example.com", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.keyId").value(KEY_ID))
                .andExpect(jsonPath("$.provider").value("razorpay"))
                .andExpect(jsonPath("$.keySecret").doesNotExist())
                .andExpect(jsonPath("$.webhookSecret").doesNotExist());
        assertThat(hookPath).startsWith("/api/v1/webhooks/razorpay/");

        HotelPaymentProvider stored = providerConfigs.findByHotelId(hotelId).orElseThrow();
        assertThat(stored.getKeySecretEnc()).isNotEqualTo(KEY_SECRET).doesNotContain(KEY_SECRET);
        assertThat(stored.getWebhookSecretEnc()).doesNotContain(WEBHOOK_SECRET);

        // saving again keeps the same webhook address
        assertThat(connectProvider()).isEqualTo(hookPath);
        assertThat(providerConfigs.count()).isEqualTo(1);

        call(get(url), "manager@example.com", null).andExpect(status().isForbidden());
        call(put(url), "manager@example.com",
                "{\"keyId\":\"%s\",\"keySecret\":\"%s\",\"webhookSecret\":\"%s\"}".formatted(KEY_ID, KEY_SECRET, WEBHOOK_SECRET))
                .andExpect(status().isForbidden());
        call(put(url), "owner@example.com",
                "{\"keyId\":\"not-a-key\",\"keySecret\":\"%s\",\"webhookSecret\":\"%s\"}".formatted(KEY_SECRET, WEBHOOK_SECRET))
                .andExpect(status().isBadRequest());
        call(get(url), null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void onlinePaymentsCannotBeSwitchedOnBeforeTheProviderIsConnected() throws Exception {
        providerConfigs.deleteAll();
        String url = "/api/v1/hotels/" + hotelId + "/payments/settings";
        String body = "{\"enabledMethods\":[\"CASH\",\"ONLINE\"],\"partialPaymentsAllowed\":true}";

        call(put(url), "owner@example.com", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Connect the payment provider before turning on online payments"));

        connectProvider();
        call(put(url), "owner@example.com", body).andExpect(status().isOk());
    }

    // ---------- the diner starts a payment ----------

    @Test
    void aDinerStartsAnOnlinePaymentForTheWholeBalance() throws Exception {
        start(anita, nextKey(), "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("razorpay"))
                .andExpect(jsonPath("$.providerOrderId").value("order_test_1"))
                .andExpect(jsonPath("$.keyId").value(KEY_ID))
                .andExpect(jsonPath("$.amountMinor").value(27000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("CREATED"));

        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        assertThat(STUB_AUTH).containsExactly(expectedAuth);
        assertThat(STUB_BODIES.get(0)).contains("27000").contains("INR");
        assertThat(attempts.count()).isEqualTo(1);
    }

    @Test
    void aDinerMayPayPartOfTheBillWhenTheHotelAllowsIt() throws Exception {
        start(anita, nextKey(), "{\"amountMinor\":10000}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amountMinor").value(10000));

        start(anita, nextKey(), "{\"amountMinor\":27001}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That is more than the amount due (270.01 INR)".replace("270.01", "270.00")));
        start(anita, nextKey(), "{\"amountMinor\":99}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The smallest online payment is 1.00 INR"));
        start(anita, nextKey(), "{\"amountMinor\":0}").andExpect(status().isBadRequest());
        assertThat(attempts.count()).isEqualTo(1);

        paymentSettings.deleteAll();
        paymentSettings.save(new PaymentSettings(hotelId, List.of(PaymentMethod.CASH, PaymentMethod.ONLINE), null,
                null, false, "u", Instant.now()));
        start(anita, nextKey(), "{\"amountMinor\":10000}").andExpect(status().isBadRequest());
        start(anita, nextKey(), "{}").andExpect(status().isCreated());
    }

    @Test
    void startingTheSamePaymentTwiceGivesTheSameAttempt() throws Exception {
        String key = nextKey();
        MvcResult first = start(anita, key, "{\"amountMinor\":10000}").andExpect(status().isCreated()).andReturn();
        String attemptId = JsonPath.read(first.getResponse().getContentAsString(), "$.attemptId");

        start(anita, key, "{\"amountMinor\":10000}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.providerOrderId").value("order_test_1"));
        start(anita, key, "{\"amountMinor\":11000}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This Idempotency-Key was already used for a different payment"));

        assertThat(attempts.count()).isEqualTo(1);
        assertThat(STUB_ORDERS.get()).isEqualTo(1);
    }

    @Test
    void startingNeedsTheKeyACookieAndAHotelThatTakesOnlinePayments() throws Exception {
        start(anita, null, "{}").andExpect(status().isBadRequest());
        start(anita, "short", "{}").andExpect(status().isBadRequest());
        start(null, nextKey(), "{}").andExpect(status().isUnauthorized());
        start("unknown-cookie", nextKey(), "{}").andExpect(status().isUnauthorized());

        paymentSettings.deleteAll(); // back to cash only
        start(anita, nextKey(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This hotel does not take online payments"));
        assertThat(attempts.count()).isZero();
    }

    @Test
    void ifTheProviderIsDownNothingIsStored() throws Exception {
        stubStatus = 500;

        start(anita, nextKey(), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The payment provider is not available right now, please try again"));

        assertThat(attempts.count()).isZero();
    }

    // ---------- the webhook ----------

    @Test
    void aSignedCaptureIsCountedOnTheBillOnce() throws Exception {
        String attemptId = startOk(27000);
        String body = event("payment.captured", orderIdOf(attemptId), "pay_1", 27000, "INR");

        signedWebhook(body).andExpect(status().isOk());
        signedWebhook(body).andExpect(status().isOk()); // the provider delivers again

        assertThat(paidOnBill()).isEqualTo(27000);
        List<Payment> recorded = payments.findAll();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getMethod()).isEqualTo(PaymentMethod.ONLINE);
        assertThat(recorded.get(0).getReference()).isEqualTo("pay_1");
        assertThat(recorded.get(0).getAmountMinor()).isEqualTo(27000);
        assertThat(attempts.findById(attemptId).orElseThrow().getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);

        mvc.perform(get("/api/v1/customer/payments/online/" + attemptId).cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        call(get("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/bill"), "cashier@example.com", null)
                .andExpect(jsonPath("$.paidMinor").value(27000)).andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void aWebhookWithABadOrMissingSignatureIsRefusedAndChangesNothing() throws Exception {
        String attemptId = startOk(27000);
        String body = event("payment.captured", orderIdOf(attemptId), "pay_1", 27000, "INR");

        webhook(hookPath, body, sign(body, "some-other-secret")).andExpect(status().isUnauthorized());
        webhook(hookPath, body, null).andExpect(status().isUnauthorized());
        webhook(hookPath, body, "").andExpect(status().isUnauthorized());
        webhook("/api/v1/webhooks/razorpay/no-such-hook", body, sign(body, WEBHOOK_SECRET))
                .andExpect(status().isNotFound());
        // a body altered after signing no longer matches
        webhook(hookPath, body.replace("27000", "1"), sign(body, WEBHOOK_SECRET)).andExpect(status().isUnauthorized());

        assertThat(paidOnBill()).isZero();
        assertThat(payments.count()).isZero();
    }

    @Test
    void aPaymentFailedEventMarksTheAttemptFailed() throws Exception {
        String attemptId = startOk(27000);

        signedWebhook(event("payment.failed", orderIdOf(attemptId), "pay_9", 27000, "INR")).andExpect(status().isOk());

        assertThat(attempts.findById(attemptId).orElseThrow().getStatus()).isEqualTo(OnlinePaymentStatus.FAILED);
        assertThat(payments.count()).isZero();
    }

    @Test
    void eventsForOrdersWeDoNotKnowAreAcknowledgedAndIgnored() throws Exception {
        signedWebhook(event("payment.captured", "order_unknown", "pay_x", 100, "INR")).andExpect(status().isOk());
        signedWebhook("{\"event\":\"something.else\",\"payload\":{}}").andExpect(status().isOk());
        assertThat(payments.count()).isZero();
    }

    @Test
    void moneyThatCannotBeCountedIsParkedForReviewNeverDropped() throws Exception {
        // 1. the captured amount differs from what was asked
        String wrongAmount = startOk(10000);
        signedWebhook(event("payment.captured", orderIdOf(wrongAmount), "pay_a", 9000, "INR")).andExpect(status().isOk());

        // 2. someone paid cash meanwhile, so the online payment would now be too much
        mongo.updateFirst(new Query(), new Update().set("paidMinor", 20000L), Bill.class);
        String tooMuch = startOk(7000); // still fits at this moment
        mongo.updateFirst(new Query(), new Update().set("paidMinor", 25000L), Bill.class); // now only 2000 is due
        signedWebhook(event("payment.captured", orderIdOf(tooMuch), "pay_b", 7000, "INR")).andExpect(status().isOk());

        // 3. the table was cleared before the money arrived
        mongo.updateFirst(new Query(), new Update().set("paidMinor", 0L), Bill.class);
        String late = startOk(5000);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("active", false), DiningSession.class);
        signedWebhook(event("payment.captured", orderIdOf(late), "pay_c", 5000, "INR")).andExpect(status().isOk());

        assertThat(payments.count()).isZero();
        call(get("/api/v1/hotels/" + hotelId + "/payments/online/review"), "cashier@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].reviewReason").value("The amount or currency captured differs from what was requested"))
                .andExpect(jsonPath("$[1].reviewReason").value("The payment is more than the amount due now"))
                .andExpect(jsonPath("$[2].reviewReason").value("The table was cleared before the payment arrived"))
                .andExpect(jsonPath("$[0].providerPaymentId").value("pay_a"));
    }

    @Test
    void onlyThoseWhoMayAdjustBillsResolveAReviewEntry() throws Exception {
        String attemptId = startOk(10000);
        signedWebhook(event("payment.captured", orderIdOf(attemptId), "pay_a", 9000, "INR"));
        String url = "/api/v1/hotels/" + hotelId + "/payments/online/" + attemptId + "/resolve";
        String note = "{\"note\":\"Refunded in the Razorpay dashboard\"}";

        call(post(url), "cashier@example.com", note).andExpect(status().isForbidden());
        call(post(url), "manager@example.com", "{\"note\":\"ab\"}").andExpect(status().isBadRequest());
        call(post(url), "manager@example.com", note)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Refunded in the Razorpay dashboard"));
        call(post(url), "manager@example.com", note).andExpect(status().isOk()); // repeating it changes nothing

        call(get("/api/v1/hotels/" + hotelId + "/payments/online/review"), "manager@example.com", null)
                .andExpect(jsonPath("$.length()").value(0));
        call(post("/api/v1/hotels/" + hotelId + "/payments/online/no-such-attempt/resolve"), "manager@example.com", note)
                .andExpect(status().isNotFound());
    }

    @Test
    void theSameCaptureArrivingTwiceAtOnceIsCountedOnce() throws Exception {
        String attemptId = startOk(27000);
        String body = event("payment.captured", orderIdOf(attemptId), "pay_1", 27000, "INR");
        String signature = sign(body, WEBHOOK_SECRET);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> mvc.perform(post(hookPath).contentType(MediaType.APPLICATION_JSON).content(body)
                    .header("X-Razorpay-Signature", signature)).andReturn().getResponse().getStatus());
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(payments.count()).isEqualTo(1);
        assertThat(paidOnBill()).isEqualTo(27000);
    }

    // ---------- staff cannot touch online money by hand ----------

    @Test
    void staffCannotRecordOrVoidAnOnlinePayment() throws Exception {
        String base = "/api/v1/hotels/" + hotelId + "/sessions/" + sessionId;

        call(post(base + "/payments").header("Idempotency-Key", "manual-online-0001"), "cashier@example.com",
                "{\"method\":\"ONLINE\",\"amountMinor\":1000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Online payments are taken through the pay link, not recorded by staff"));

        String attemptId = startOk(27000);
        signedWebhook(event("payment.captured", orderIdOf(attemptId), "pay_1", 27000, "INR")).andExpect(status().isOk());
        String paymentId = payments.findAll().get(0).getId();

        call(post(base + "/payments/" + paymentId + "/void"), "manager@example.com", "{\"reason\":\"Changed my mind\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Online payments are refunded from the payment provider, not voided here"));
        assertThat(paidOnBill()).isEqualTo(27000);
    }

    @Test
    void aDinerCannotLookAtAnotherTablesAttempt() throws Exception {
        String attemptId = startOk(10000);
        mvc.perform(get("/api/v1/customer/payments/online/" + attemptId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customer/payments/online/" + attemptId).cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREATED"));
        mvc.perform(get("/api/v1/customer/payments/online/no-such-attempt").cookie(new Cookie("qr_customer", anita)))
                .andExpect(status().isNotFound());
    }
}