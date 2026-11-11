package com.example.qrorder.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Opt-in: mvn verify "-Dit.test=LoadSmokeIT" "-Dload.test=true"
 * Options: -Dload.guests=200 -Dload.orders=2 -Dload.tables=40 -Dload.p95ms=1000 (the design target is 300)
 * Numbers from a laptop with a throwaway database are a smoke test for contention and errors, not a benchmark.
 */
@EnabledIfSystemProperty(named = "load.test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoadSmokeIT extends AbstractIntegrationTest {

    private static final int GUESTS = Integer.getInteger("load.guests", 200);
    private static final int ORDERS_PER_GUEST = Integer.getInteger("load.orders", 2);
    private static final int TABLES = Integer.getInteger("load.tables", 40);
    private static final int WARMUP_GUESTS = 20;
    private static final long P95_LIMIT_MS = Long.getLong("load.p95ms", 1000L);
    private static final int RAMP_SECONDS = Integer.getInteger("load.rampSeconds", 0);
    private static final int THINK_MS = Integer.getInteger("load.thinkMs", 0);
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @LocalServerPort int port;
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
    @Autowired PasswordEncoder encoder;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private String hotelId;
    private final List<String> itemIds = new ArrayList<>();

    private void seed() {
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
        Instant now = Instant.now();

        hotelId = hotels.save(new Hotel("load-hotel", "Load Hotel", "x", null, "Asia/Kolkata", "INR", now)).getId();
        User cook = new User("cook@example.com", encoder.encode(PASSWORD), "Cook");
        cook.setEmailVerified(true);
        cook = users.save(cook);
        memberships.save(new HotelMembership(hotelId, cook.getId(), Set.of(HotelRole.KITCHEN), cook.getId(), now));
        for (int i = 0; i < TABLES; i++) {
            String tableId = tables.save(new DiningTable(hotelId, "T" + i, 4, null, now)).getId();
            credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-load-" + i), "u", now));
        }
        String category = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        for (int i = 0; i < 6; i++) {
            itemIds.add(items.save(new MenuItem(hotelId, category, "Dish " + i, null, 10000L + i * 1000L, Diet.VEG, i,
                    now)).getId());
        }
    }

    // ---------- http helpers ----------

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30));
    }

    private HttpResponse<String> send(String label, HttpRequest.Builder request, Set<Integer> expected) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            latencies.computeIfAbsent(label, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add((System.nanoTime() - started) / 1_000_000);
            if (!expected.contains(response.statusCode())) {
                String body = response.body() == null ? "" : response.body();
                failures.add(label + " -> " + response.statusCode() + " " + body.substring(0, Math.min(120, body.length())));
            }
            return response;
        } catch (Exception e) {
            failures.add(label + " threw " + e);
            return null;
        }
    }

    private static HttpRequest.BodyPublisher json(String body) {
        return HttpRequest.BodyPublishers.ofString(body);
    }

    // ---------- the scenario ----------
    private static void think() {
        if (THINK_MS <= 0) {
            return;
        }
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(THINK_MS / 2, THINK_MS + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void guest(int n) {
        HttpResponse<String> scan = send("scan", req("/api/v1/scan").header("Content-Type", "application/json")
                .POST(json("{\"token\":\"tok-load-" + (n % TABLES) + "\"}")), Set.of(200));
        if (scan == null || scan.statusCode() != 200) {
            return;
        }
        Matcher cookie = COOKIE_VALUE.matcher(scan.headers().firstValue("set-cookie").orElse(""));
        if (!cookie.find()) {
            failures.add("scan gave no cookie");
            return;
        }
        String cookieHeader = "qr_customer=" + cookie.group(1);
        send("menu", req("/api/v1/public/hotels/" + hotelId + "/menu").GET(), Set.of(200));
        for (int i = 0; i < ORDERS_PER_GUEST; i++) {
            String item = itemIds.get((n + i) % itemIds.size());
            send("order", req("/api/v1/customer/orders").header("Content-Type", "application/json")
                    .header("Cookie", cookieHeader).header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(json("{\"lines\":[{\"itemId\":\"" + item + "\",\"quantity\":1}]}")), Set.of(201));
        }
        send("my-orders", req("/api/v1/customer/orders").header("Cookie", cookieHeader).GET(), Set.of(200));
    }

    private void kitchen(String token, AtomicBoolean done) {
        while (!done.get()) {
            HttpResponse<String> list = send("kitchen-list", req("/api/v1/hotels/" + hotelId + "/kitchen/orders")
                    .header("Authorization", "Bearer " + token).GET(), Set.of(200));
            if (list != null && list.statusCode() == 200) {
                List<String> pending = JsonPath.read(list.body(), "$[?(@.status=='PENDING')].id");
                if (!pending.isEmpty()) {
                    send("kitchen-accept", req("/api/v1/hotels/" + hotelId + "/kitchen/orders/" + pending.get(0)
                            + "/accept").header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody()), Set.of(200, 409));
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private long runGuests(int guests, String kitchenToken) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch go = new CountDownLatch(1);
        long started;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> kitchenCrew = new ArrayList<>();
            if (kitchenToken != null) {
                for (int i = 0; i < 4; i++) {
                    kitchenCrew.add(pool.submit(() -> kitchen(kitchenToken, done)));
                }
            }
            List<Future<?>> crowd = new ArrayList<>();
            for (int n = 0; n < guests; n++) {
                int guestNumber = n;
                crowd.add(pool.submit(() -> {
                    go.await();
                    if (RAMP_SECONDS > 0) {
                        Thread.sleep(RAMP_SECONDS * 1000L * guestNumber / guests);
                    }
                    guest(guestNumber);
                    return null;
                }));
            }
            started = System.nanoTime();
            go.countDown();
            for (Future<?> f : crowd) {
                f.get(180, TimeUnit.SECONDS);
            }
            done.set(true);
            for (Future<?> f : kitchenCrew) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    @Test
    void manyGuestsOrderAtOnceWhileTheKitchenWorks() throws Exception {
        seed();
        HttpResponse<String> login = send("login", req("/api/v1/auth/login").header("Content-Type", "application/json")
                .POST(json("{\"email\":\"cook@example.com\",\"password\":\"" + PASSWORD + "\"}")), Set.of(200));
        String token = JsonPath.read(login.body(), "$.accessToken");

        runGuests(WARMUP_GUESTS, null); // not measured
        long warmOrders = orders.count();
        long warmCustomers = customers.count();
        latencies.clear();

        long wallMs = runGuests(GUESTS, token);

        StringBuilder report = new StringBuilder();
        report.append(String.format("Load smoke: %d guests, %d orders each, %d tables, %d ms wall time%n",
                GUESTS, ORDERS_PER_GUEST, TABLES, wallMs));
        report.append(String.format("%-16s %7s %7s %7s %7s %7s%n", "endpoint", "count", "p50", "p95", "p99", "max"));
        long requests = 0;
        Map<String, Long> p95 = new TreeMap<>();
        for (Map.Entry<String, List<Long>> entry : new TreeMap<>(latencies).entrySet()) {
            List<Long> sorted = new ArrayList<>(entry.getValue());
            Collections.sort(sorted);
            requests += sorted.size();
            p95.put(entry.getKey(), percentile(sorted, 0.95));
            report.append(String.format("%-16s %7d %7d %7d %7d %7d%n", entry.getKey(), sorted.size(),
                    percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                    sorted.get(sorted.size() - 1)));
        }
        report.append(String.format("requests: %d, throughput: %d/s, failures: %d%n", requests,
                wallMs == 0 ? 0 : requests * 1000 / wallMs, failures.size()));
        long worstGuestP95 = p95.entrySet().stream().filter(e -> Set.of("scan", "menu", "order", "my-orders")
                .contains(e.getKey())).mapToLong(Map.Entry::getValue).max().orElse(0);
        report.append(String.format("Design target (guest APIs p95 < 300 ms): %s (worst p95 %d ms)%n",
                worstGuestP95 < 300 ? "MET" : "NOT MET on this machine", worstGuestP95));
        System.out.println(report);
        Files.writeString(Path.of("target", "load-report.txt"), report.toString());

        assertThat(failures).as("unexpected responses").isEmpty();
        assertThat(orders.count() - warmOrders).isEqualTo((long) GUESTS * ORDERS_PER_GUEST);
        assertThat(customers.count() - warmCustomers).isEqualTo(GUESTS);
        assertThat(sessions.findAll().stream().filter(DiningSession::isActive).count())
                .as("one open session per table scanned, however many guests scanned at once")
                .isEqualTo((long) Math.min(Math.max(GUESTS, WARMUP_GUESTS), TABLES));
        assertThat(worstGuestP95).as("p95 of the guest APIs (limit %d ms)", P95_LIMIT_MS).isLessThanOrEqualTo(P95_LIMIT_MS);
    }
}