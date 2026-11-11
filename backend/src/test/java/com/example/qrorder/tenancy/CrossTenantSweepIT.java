package com.example.qrorder.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.repository.BillRepository;
import com.example.qrorder.billing.repository.PaymentRepository;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.common.security.Tokens;
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
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Finds every route in the application and attacks it from the other hotel. Nothing is listed by hand except sample
 * ids per path variable: a route using a variable this test does not know about fails the test, so new routes
 * cannot silently escape the sweep.
 */
class CrossTenantSweepIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");
    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z]+)(?::[^}]*)?}");
    private static final String NO_SUCH_HOTEL = "64b0000000000000000000ff";
    /** Ids that look right but belong to nobody: sweeps skip these as "foreign" ids, but they fill other slots. */
    private static final Map<String, String> FAKES = Map.of(
            "requestId", "64b000000000000000000001",
            "discountId", "64b000000000000000000002",
            "attemptId", "64b000000000000000000003",
            "lineNo", "1");

    /** A 400 or 415 means the route rejected my empty body before looking at the id: not proven either way. */
    private static final Set<Integer> NOT_EXERCISED = Set.of(400, 415);
    /** Idempotent "clear" routes that answer 204 when there is nothing to clear. Reviewed; snapshot still guards them. */
    private static final Set<String> IDEMPOTENT_NOOPS = Set.of(
            "DELETE /api/v1/hotels/{hotelId}/tables/{tableId}/waiter",
            "POST /api/v1/hotels/{hotelId}/tables/{tableId}/waiter/release");

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
    @Autowired PasswordEncoder encoder;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    record Route(String method, String pattern) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    record Reply(int status, String signature) {
    }

    record Dataset(String hotelId, String token, String cookie, Map<String, String> ids) {
    }

    Dataset a;
    Dataset b;
    final List<String> failures = new ArrayList<>();
    final Set<String> unmapped = new TreeSet<>();
    final List<String> notExercised = new ArrayList<>();
    final List<String> skippedFakes = new ArrayList<>();
    final List<String> acceptedNoops = new ArrayList<>();

    @BeforeEach
    void seedTwoHotels() throws Exception {
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
        failures.clear();
        unmapped.clear();
        notExercised.clear();
        skippedFakes.clear();
        acceptedNoops.clear();
        a = seedHotel("a");
        b = seedHotel("b");
    }

    // ---------- seeding: every kind of object a route can name, in each hotel ----------

    private User member(String hotelId, String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
        return user;
    }

    private Dataset seedHotel(String tag) throws Exception {
        Instant now = Instant.now();
        User owner = new User("owner-" + tag + "@example.com", encoder.encode(PASSWORD), "Owner " + tag);
        owner.setEmailVerified(true);
        owner = users.save(owner);
        String hotelId = hotels.save(new Hotel("hotel-" + tag, "Hotel " + tag, owner.getId(), null, "Asia/Kolkata",
                "INR", now)).getId();
        memberships.save(new HotelMembership(hotelId, owner.getId(), Set.of(HotelRole.OWNER), owner.getId(), now));
        member(hotelId, "cashier-" + tag + "@example.com", "Cashier " + tag, HotelRole.CASHIER);

        String tableId = tables.save(new DiningTable(hotelId, "T1", 4, null, now)).getId();
        credentials.save(new QrCredential(hotelId, tableId, Tokens.sha256("tok-" + tag), "u", now));
        String categoryId = categories.save(new MenuCategory(hotelId, "Menu", null, 1, now)).getId();
        String itemId = items.save(new MenuItem(hotelId, categoryId, "Soup", null, 15000L, Diet.VEG, 0, now)).getId();
        paymentSettings.save(new PaymentSettings(hotelId, List.of(PaymentMethod.CASH), null, null, true, "u", now));

        String header = mvc.perform(post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-" + tag + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        String cookie = matcher.group(1);

        MvcResult placed = mvc.perform(post("/api/v1/customer/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":\"%s\",\"quantity\":1}]}".formatted(itemId))
                        .cookie(new Cookie("qr_customer", cookie)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        String orderId = JsonPath.read(placed.getResponse().getContentAsString(), "$.id");
        mongo.updateMulti(Query.query(where("hotelId").is(hotelId)), new Update().set("status", OrderStatus.DELIVERED),
                Order.class);
        String sessionId = sessions.findAll().stream().filter(s -> s.getHotelId().equals(hotelId)).findFirst()
                .orElseThrow().getId();

        String token = login("owner-" + tag + "@example.com");
        MvcResult paid = mvc.perform(post("/api/v1/hotels/" + hotelId + "/sessions/" + sessionId + "/payments")
                        .header("Idempotency-Key", "sweep-pay-" + tag + "-0001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("cashier-" + tag + "@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amountMinor\":1000}"))
                .andExpect(status().isCreated()).andReturn();
        String paymentId = JsonPath.read(paid.getResponse().getContentAsString(), "$.payment.id");

        String billId = bills.findAll().stream().filter(x -> hotelId.equals(x.getHotelId()))
                .map(x -> x.getId()).findFirst().orElse("64b000000000000000000004");

        return new Dataset(hotelId, token, cookie, Map.of("tableId", tableId, "sessionId", sessionId,
                "orderId", orderId, "itemId", itemId, "categoryId", categoryId, "paymentId", paymentId, "billId", billId,
                "userId", owner.getId()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    // ---------- finding and calling every route ----------

    private List<Route> routes() {
        List<Route> found = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of() : info.getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/v1/")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    found.add(new Route("GET", pattern));
                }
                for (RequestMethod method : methods) {
                    found.add(new Route(method.name(), pattern));
                }
            }
        }
        found.sort(Comparator.comparing(Route::pattern).thenComparing(Route::method));
        return found;
    }

    private static List<String> variablesOf(String pattern) {
        List<String> names = new ArrayList<>();
        Matcher matcher = VAR.matcher(pattern);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private String fill(String pattern, Function<String, String> valueOf) {
        Matcher matcher = VAR.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = valueOf.apply(matcher.group(1));
            if (value == null) {
                unmapped.add(matcher.group(1));
                value = "unmapped";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String idOf(Dataset data, String variable) {
        String real = data.ids().get(variable);
        return real != null ? real : FAKES.get(variable);
    }

    private Reply call(Route route, String url, String token, String cookie) {
        try {
            var builder = request(HttpMethod.valueOf(route.method()), url)
                    .header("Idempotency-Key", "sweep-key-0123456789abcdef");
            if (token != null) {
                builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            if (cookie != null) {
                builder.cookie(new Cookie("qr_customer", cookie));
            }
            if (Set.of("POST", "PUT", "PATCH").contains(route.method())) {
                builder.contentType(MediaType.APPLICATION_JSON).content("{}");
            }
            MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
            return new Reply(response.getStatus(), signature(response.getContentAsString()));
        } catch (Exception e) {
            return new Reply(-1, "threw " + e);
        }
    }

    /** What a caller could learn from the body, without the per-request trace id. */
    private static String signature(String body) {
        try {
            return JsonPath.<String>read(body, "$.code") + "|" + JsonPath.<String>read(body, "$.detail");
        } catch (Exception e) {
            return body == null ? "" : body.substring(0, Math.min(60, body.length()));
        }
    }

    private void fail(String sweep, Route route, String url, Reply reply, String expected) {
        failures.add("[" + sweep + "] " + route + " (" + url + ") -> " + reply.status() + " " + reply.signature()
                + "  expected " + expected);
    }

    // ---------- the sweeps ----------

    /** 1 and 2: another hotel's owner, and nobody at all, against every hotel-scoped route. */
    private void sweepWrongHotel(List<Route> all) {
        for (Route route : all) {
            if (!route.pattern().contains("{hotelId}")) {
                continue;
            }
            String url = fill(route.pattern(), n -> n.equals("hotelId") ? a.hotelId() : idOf(a, n));
            String urlGone = fill(route.pattern(), n -> n.equals("hotelId") ? NO_SUCH_HOTEL : idOf(a, n));
            Reply outsider = call(route, url, b.token(), null);
            Reply gone = call(route, urlGone, b.token(), null);
            Reply anonymous = call(route, url, null, null);

            if (outsider.status() != 404 && !NOT_EXERCISED.contains(outsider.status())) {
                fail("wrong hotel", route, url, outsider, "404");
            } else if (NOT_EXERCISED.contains(outsider.status())) {
                notExercised.add("wrong hotel: " + route + " -> " + outsider.status());
            }
            if (outsider.status() != gone.status() || !outsider.signature().equals(gone.signature())) {
                fail("hotel ids can be probed", route, url, outsider,
                        "the same answer as a hotel that does not exist (" + gone.status() + " " + gone.signature() + ")");
            }
            if (anonymous.status() != 401) {
                fail("no login", route, url, anonymous, "401");
            }
        }
    }

    /** 3: inside hotel B, one id at a time swapped for an id that belongs to hotel A. */
    private void sweepForeignIds(List<Route> all) {
        for (Route route : all) {
            if (!route.pattern().contains("{hotelId}")) {
                continue;
            }
            for (String foreign : variablesOf(route.pattern())) {
                if (foreign.equals("hotelId")) {
                    continue;
                }
                if (FAKES.containsKey(foreign)) {
                    skippedFakes.add(route + " {" + foreign + "}");
                    continue;
                }
                String url = fill(route.pattern(), n -> n.equals("hotelId") ? b.hotelId()
                        : n.equals(foreign) ? idOf(a, n) : idOf(b, n));
                Reply reply = call(route, url, b.token(), null);
                if (reply.status() == 404) {
                    continue;
                }
                if (reply.status() == 204 && IDEMPOTENT_NOOPS.contains(route.toString())) {
                    acceptedNoops.add(route + " {" + foreign + "}");
                } else if (NOT_EXERCISED.contains(reply.status())) {
                    notExercised.add("foreign " + foreign + ": " + route + " -> " + reply.status());
                } else {
                    fail("foreign " + foreign, route, url, reply, "404");
                }
            }
        }
    }

    /** 4: a guest of hotel B on every customer route that names an id, with hotel A's id. */
    private void sweepGuestRoutes(List<Route> all) {
        for (Route route : all) {
            List<String> vars = variablesOf(route.pattern());
            if (!route.pattern().startsWith("/api/v1/customer") || vars.isEmpty()) {
                continue;
            }
            for (String foreign : vars) {
                if (FAKES.containsKey(foreign)) {
                    skippedFakes.add(route + " {" + foreign + "}");
                    continue;
                }
                String url = fill(route.pattern(), n -> n.equals(foreign) ? idOf(a, n) : idOf(b, n));
                Reply reply = call(route, url, null, b.cookie());
                if (reply.status() == 404) {
                    continue;
                }
                if (NOT_EXERCISED.contains(reply.status())) {
                    notExercised.add("guest foreign " + foreign + ": " + route + " -> " + reply.status());
                } else {
                    fail("guest foreign " + foreign, route, url, reply, "404");
                }
            }
        }
    }

    /** 5: a hotel owner on every platform-admin route. */
    private void sweepAdminRoutes(List<Route> all) {
        for (Route route : all) {
            if (!route.pattern().startsWith("/api/v1/admin")) {
                continue;
            }
            String url = fill(route.pattern(), n -> a.hotelId());
            Reply owner = call(route, url, b.token(), null);
            Reply anonymous = call(route, url, null, null);
            if (owner.status() != 403) {
                fail("admin route as an owner", route, url, owner, "403");
            }
            if (anonymous.status() != 401) {
                fail("admin route with no login", route, url, anonymous, "401");
            }
        }
    }

    // ---------- 6: a refused request leaves nothing behind ----------

    private Map<String, List<String>> snapshot() {
        List<String> ids = List.of(a.hotelId(), b.hotelId());
        Map<String, List<String>> out = new TreeMap<>();
        for (String name : mongo.getCollectionNames()) {
            if (name.startsWith("system.")) {
                continue;
            }
            List<String> docs = new ArrayList<>();
            mongo.getCollection(name).find(new Document("hotelId", new Document("$in", ids)))
                    .sort(new Document("_id", 1)).forEach(d -> docs.add(d.toJson()));
            if (!docs.isEmpty()) {
                out.put(name, docs);
            }
        }
        List<String> hotelDocs = new ArrayList<>();
        mongo.getCollection(mongo.getCollectionName(Hotel.class)).find().sort(new Document("_id", 1))
                .forEach(d -> hotelDocs.add(d.toJson()));
        out.put("(hotels)", hotelDocs);
        return out;
    }

    // ---------- the tests ----------

    @Test
    void noRouteLetsOneHotelReachOrChangeAnotherHotelsData() {
        List<Route> all = routes();
        Map<String, List<String>> before = snapshot();

        sweepWrongHotel(all);
        sweepForeignIds(all);
        sweepGuestRoutes(all);
        sweepAdminRoutes(all);

        Map<String, List<String>> after = snapshot();
        for (String collection : before.keySet()) {
            if (!before.get(collection).equals(after.get(collection))) {
                failures.add("[nothing may change] collection '" + collection + "' was changed by refused requests");
            }
        }
        if (!unmapped.isEmpty()) {
            failures.add("[sweep setup] routes use path variables this test has no sample id for: " + unmapped
                    + " (add them to seedHotel or FAKES)");
        }

        System.out.printf("Cross-tenant sweep: %d routes found, %d checks not exercised (rejected my empty body "
                + "first), %d fake-id variables skipped%n", all.size(), notExercised.size(), skippedFakes.size());
        notExercised.forEach(line -> System.out.println("  not exercised: " + line));
        skippedFakes.forEach(line -> System.out.println("  no real object to swap in: " + line));
        acceptedNoops.forEach(line -> System.out.println("  accepted idempotent no-op (204): " + line));

        assertThat(failures).as("cross-tenant failures").isEmpty();
    }

    @Test
    void theSweepActuallyFindsTheRoutesItIsMeantToAttack() {
        List<Route> all = routes();
        long hotelScoped = all.stream().filter(r -> r.pattern().contains("{hotelId}")).count();
        long withObjectIds = all.stream().filter(r -> r.pattern().contains("{hotelId}")
                && variablesOf(r.pattern()).size() > 1).count();
        long admin = all.stream().filter(r -> r.pattern().startsWith("/api/v1/admin")).count();
        long guestWithIds = all.stream().filter(r -> r.pattern().startsWith("/api/v1/customer")
                && !variablesOf(r.pattern()).isEmpty()).count();

        assertThat(hotelScoped).as("hotel-scoped routes").isGreaterThanOrEqualTo(40);
        assertThat(withObjectIds).as("hotel routes naming another object").isGreaterThanOrEqualTo(15);
        assertThat(admin).as("admin routes").isGreaterThanOrEqualTo(5);
        assertThat(guestWithIds).as("guest routes naming an id").isGreaterThanOrEqualTo(1);
    }
}