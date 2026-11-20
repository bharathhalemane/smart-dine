package com.example.qrorder.earnings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.admin.repository.AdminAuditRepository;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsSettings;
import com.example.qrorder.earnings.repository.EarningsEntryRepository;
import com.example.qrorder.earnings.repository.EarningsSettingsRepository;
import com.example.qrorder.earnings.service.EarningsLedger;
import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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

class EarningsApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired EarningsEntryRepository entries;
    @Autowired EarningsSettingsRepository settings;
    @Autowired AdminAuditRepository audit;
    @Autowired EarningsLedger ledger;
    @Autowired PasswordEncoder encoder;

    String hotelA;
    String hotelB;
    String ownerA;
    String cashierA;
    String ownerB;
    String admin;

    /*
     * Hotel A (Asia/Kolkata, commission 2%):
     *   p1  10000 paid, fee 236            at 2026-11-10T20:00Z = 11 Nov 01:30 India  -> net 9564
     *   p2   5000 paid, fee not reported   at 2026-11-10T10:00Z = 10 Nov 15:30 India  -> net 4900
     *   p2 refunded in full                at 2026-11-11T05:00Z = 11 Nov 10:30 India  -> net -4900
     *   payout 3000                        at 2026-11-11T08:00Z = 11 Nov 13:30 India
     * Hotel B (no own rate, default 0%): one payment of 1000, fee not reported.
     */
    @BeforeEach
    void seed() throws Exception {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        entries.deleteAll();
        settings.deleteAll();
        audit.deleteAll();
        Instant now = Instant.now();

        User adminUser = account("admin@example.com");
        adminUser.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        users.save(adminUser);
        User oa = users.save(account("owner-a@example.com"));
        User ca = users.save(account("cashier-a@example.com"));
        User ob = users.save(account("owner-b@example.com"));

        hotelA = hotels.save(new Hotel("earn-a", "Hotel A", oa.getId(), null, "Asia/Kolkata", "INR", now)).getId();
        hotelB = hotels.save(new Hotel("earn-b", "Hotel B", ob.getId(), null, "Asia/Kolkata", "INR", now)).getId();
        memberships.save(new HotelMembership(hotelA, oa.getId(), Set.of(HotelRole.OWNER), oa.getId(), now));
        memberships.save(new HotelMembership(hotelA, ca.getId(), Set.of(HotelRole.CASHIER), oa.getId(), now));
        memberships.save(new HotelMembership(hotelB, ob.getId(), Set.of(HotelRole.OWNER), ob.getId(), now));
        settings.save(new EarningsSettings(hotelA, 200, "seed", now));

        ledger.recordOnlineCapture(hotelA, "p1", "b1", "s1", "pay_1", 10_000, 236L, Instant.parse("2026-11-10T20:00:00Z"));
        ledger.recordOnlineCapture(hotelA, "p2", "b2", "s2", "pay_2", 5_000, null, Instant.parse("2026-11-10T10:00:00Z"));
        ledger.recordReversal(hotelA, "p2", "Guest refunded", Instant.parse("2026-11-11T05:00:00Z"));
        entries.save(EarningsEntry.payout(hotelA, "po1", 3_000, "INR", "Weekly payout", Instant.parse("2026-11-11T08:00:00Z"), now));
        ledger.recordOnlineCapture(hotelB, "pB", "bB", "sB", "pay_B", 1_000, null, Instant.parse("2026-11-11T09:00:00Z"));

        ownerA = login("owner-a@example.com");
        cashierA = login("cashier-a@example.com");
        ownerB = login("owner-b@example.com");
        admin = login("admin@example.com");
    }

    // ---------- helpers ----------

    private User account(String email) {
        User user = new User(email, encoder.encode(PASSWORD), email);
        user.setEmailVerified(true);
        return user;
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions getAs(String token, String url) throws Exception {
        var request = get(url);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private ResultActions putAs(String token, String url, String json) throws Exception {
        var request = put(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private String earnings(String hotel) {
        return "/api/v1/hotels/" + hotel + "/earnings";
    }

    private List<AdminAuditEntry> commissionAudit(String hotel) {
        return audit.findFirst100ByTargetIdOrderByAtDescIdDesc(hotel).stream()
                .filter(e -> e.getAction() == AdminAuditAction.HOTEL_COMMISSION_CHANGED).toList();
    }

    // ---------- the owner's report ----------

    @Test
    void theOwnerSeesTotalsAndEachDayInTheHotelsOwnTimezone() throws Exception {
        getAs(ownerA, earnings(hotelA) + "?from=2026-11-10&to=2026-11-11").andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.totals.payments").value(2))
                .andExpect(jsonPath("$.totals.collectedMinor").value(15000))
                .andExpect(jsonPath("$.totals.refundedMinor").value(5000))
                .andExpect(jsonPath("$.totals.feeMinor").value(236))
                .andExpect(jsonPath("$.totals.commissionMinor").value(200))
                .andExpect(jsonPath("$.totals.payoutsMinor").value(3000))
                .andExpect(jsonPath("$.totals.netEarnedMinor").value(9564))
                .andExpect(jsonPath("$.balanceMinor").value(6564))
                .andExpect(jsonPath("$.paymentsWithoutActualFee").value(1))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].date").value("2026-11-10"))
                .andExpect(jsonPath("$.days[0].collectedMinor").value(5000))
                .andExpect(jsonPath("$.days[0].commissionMinor").value(100))
                .andExpect(jsonPath("$.days[0].netMinor").value(4900))
                .andExpect(jsonPath("$.days[1].date").value("2026-11-11"))
                .andExpect(jsonPath("$.days[1].collectedMinor").value(10000))
                .andExpect(jsonPath("$.days[1].refundedMinor").value(5000))
                .andExpect(jsonPath("$.days[1].feeMinor").value(236))
                .andExpect(jsonPath("$.days[1].commissionMinor").value(100))
                .andExpect(jsonPath("$.days[1].payoutsMinor").value(3000))
                .andExpect(jsonPath("$.days[1].netMinor").value(4664));
    }

    @Test
    void theBalanceAlwaysEqualsCollectedMinusRefundsFeesCommissionAndPayouts() throws Exception {
        String body = getAs(ownerA, earnings(hotelA) + "?from=2026-11-01&to=2026-11-30").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long collected = ((Number) JsonPath.read(body, "$.totals.collectedMinor")).longValue();
        long refunded = ((Number) JsonPath.read(body, "$.totals.refundedMinor")).longValue();
        long fee = ((Number) JsonPath.read(body, "$.totals.feeMinor")).longValue();
        long commission = ((Number) JsonPath.read(body, "$.totals.commissionMinor")).longValue();
        long payouts = ((Number) JsonPath.read(body, "$.totals.payoutsMinor")).longValue();
        long balance = ((Number) JsonPath.read(body, "$.balanceMinor")).longValue();
        assertThat(balance).isEqualTo(collected - refunded - fee - commission - payouts);
    }

    @Test
    void withoutDatesItShowsTheLastThirtyDays() throws Exception {
        getAs(ownerA, earnings(hotelA)).andExpect(status().isOk()).andExpect(jsonPath("$.days.length()").value(30));
    }

    @Test
    void badDateRangesAreRefused() throws Exception {
        getAs(ownerA, earnings(hotelA) + "?from=2026-11-11&to=2026-11-10").andExpect(status().isBadRequest());
        getAs(ownerA, earnings(hotelA) + "?from=2026-01-01&to=2026-04-05").andExpect(status().isBadRequest()); // 95 days
        getAs(ownerA, earnings(hotelA) + "?from=yesterday").andExpect(status().isBadRequest());
        getAs(ownerA, earnings(hotelA) + "?from=2026-01-01&to=2026-04-03").andExpect(status().isOk()); // exactly 93 days
    }

    @Test
    void theLedgerLinesAreListedNewestFirstAndCanBeFiltered() throws Exception {
        getAs(ownerA, earnings(hotelA) + "/entries?size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].type").value("PAYOUT"))
                .andExpect(jsonPath("$.items[1].type").value("REFUND"));
        getAs(ownerA, earnings(hotelA) + "/entries?size=2&page=1").andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].paymentId").value("p1"));
        getAs(ownerA, earnings(hotelA) + "/entries?type=REFUND").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].netMinor").value(-4900));
        getAs(ownerA, earnings(hotelA) + "/entries?type=NONSENSE").andExpect(status().isBadRequest());
        getAs(ownerA, earnings(hotelA) + "/entries?size=101").andExpect(status().isBadRequest());
    }

    @Test
    void onlyThePermittedOfTheRightHotelCanSeeIt() throws Exception {
        getAs(null, earnings(hotelA)).andExpect(status().isUnauthorized());
        getAs(cashierA, earnings(hotelA)).andExpect(status().isForbidden());
        getAs(cashierA, earnings(hotelA) + "/entries").andExpect(status().isForbidden());
        getAs(ownerB, earnings(hotelA)).andExpect(status().isNotFound());
        getAs(ownerB, earnings(hotelA) + "/entries").andExpect(status().isNotFound());
        getAs(ownerB, earnings(hotelB) + "?from=2026-11-01&to=2026-11-30").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.collectedMinor").value(1000));
    }

    // ---------- the platform admin's view ----------

    @Test
    void theAdminSeesEveryHotelsBalanceLargestFirst() throws Exception {
        String body = getAs(admin, "/api/v1/admin/earnings").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).as("admin earnings response").contains("\"hotelId\":\"" + hotelA + "\"");

        getAs(admin, "/api/v1/admin/earnings").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].hotelId").value(hotelA))
                .andExpect(jsonPath("$.items[0].hotelName").value("Hotel A"))
                .andExpect(jsonPath("$.items[0].collectedMinor").value(15000))
                .andExpect(jsonPath("$.items[0].payoutsMinor").value(3000))
                .andExpect(jsonPath("$.items[0].balanceMinor").value(6564))
                .andExpect(jsonPath("$.items[0].commissionBps").value(200))
                .andExpect(jsonPath("$.items[1].hotelId").value(hotelB))
                .andExpect(jsonPath("$.items[1].balanceMinor").value(1000))
                .andExpect(jsonPath("$.items[1].commissionBps").value(0));
        getAs(admin, "/api/v1/admin/earnings?size=1&page=1").andExpect(jsonPath("$.items[0].hotelId").value(hotelB));
        getAs(admin, "/api/v1/admin/hotels/" + hotelA + "/earnings?from=2026-11-10&to=2026-11-11")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balanceMinor").value(6564));
        getAs(admin, "/api/v1/admin/hotels/no-such-hotel/earnings").andExpect(status().isNotFound());
    }

    @Test
    void theAdminRoutesAreClosedToEveryoneElse() throws Exception {
        for (String url : List.of("/api/v1/admin/earnings", "/api/v1/admin/hotels/" + hotelA + "/earnings",
                "/api/v1/admin/hotels/" + hotelA + "/commission")) {
            getAs(null, url).andExpect(status().isUnauthorized());
            getAs(ownerA, url).andExpect(status().isForbidden()); // not even the hotel's own owner
        }
    }

    // ---------- the commission rate ----------

    @Test
    void theAdminSeesWhetherAHotelHasItsOwnRate() throws Exception {
        getAs(admin, "/api/v1/admin/hotels/" + hotelA + "/commission").andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionBps").value(200)).andExpect(jsonPath("$.custom").value(true));
        getAs(admin, "/api/v1/admin/hotels/" + hotelB + "/commission").andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionBps").value(0)).andExpect(jsonPath("$.custom").value(false));
    }

    @Test
    void changingTheRateIsAuditedAndOnlyAffectsLaterPayments() throws Exception {
        putAs(admin, "/api/v1/admin/hotels/" + hotelB + "/commission", "{\"commissionBps\":300,\"reason\":\"Agreed rate\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.commissionBps").value(300))
                .andExpect(jsonPath("$.custom").value(true));

        List<AdminAuditEntry> log = commissionAudit(hotelB);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).getFromStatus()).isEqualTo("0 bps");
        assertThat(log.get(0).getToStatus()).isEqualTo("300 bps");
        assertThat(log.get(0).getReason()).isEqualTo("Agreed rate");
        assertThat(log.get(0).getActorEmail()).isEqualTo("admin@example.com");

        EarningsEntry later = ledger.recordOnlineCapture(hotelB, "pB2", "bB", "sB", "pay_B2", 10_000, 0L, Instant.now());
        assertThat(later.getCommissionBps()).isEqualTo(300);
        assertThat(later.getCommissionMinor()).isEqualTo(300);
        assertThat(entries.findByUniqueKey(EarningsEntry.keyOf(
                        com.example.qrorder.earnings.model.EarningsEntryType.ONLINE_PAYMENT, hotelB, "pB"))
                .orElseThrow().getCommissionBps()).as("the earlier payment keeps its old rate").isZero();
    }

    @Test
    void settingTheSameRateAgainChangesAndAuditsNothing() throws Exception {
        putAs(admin, "/api/v1/admin/hotels/" + hotelA + "/commission", "{\"commissionBps\":200,\"reason\":\"No change\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.commissionBps").value(200));
        assertThat(commissionAudit(hotelA)).isEmpty();
    }

    @Test
    void badRatesAndMissingReasonsAreRefused() throws Exception {
        String url = "/api/v1/admin/hotels/" + hotelB + "/commission";
        putAs(admin, url, "{\"commissionBps\":3001,\"reason\":\"Too high\"}").andExpect(status().isBadRequest());
        putAs(admin, url, "{\"commissionBps\":-1,\"reason\":\"Negative\"}").andExpect(status().isBadRequest());
        putAs(admin, url, "{\"reason\":\"No rate\"}").andExpect(status().isBadRequest());
        putAs(admin, url, "{\"commissionBps\":300}").andExpect(status().isBadRequest());
        putAs(admin, url, "{\"commissionBps\":300,\"reason\":\"ab\"}").andExpect(status().isBadRequest());
        putAs(admin, "/api/v1/admin/hotels/no-such-hotel/commission", "{\"commissionBps\":300,\"reason\":\"Missing hotel\"}")
                .andExpect(status().isNotFound());
        assertThat(commissionAudit(hotelB)).isEmpty();
        assertThat(settings.existsByHotelId(hotelB)).isFalse();
    }

    @Test
    void onlyAPlatformAdminCanChangeTheRate() throws Exception {
        String url = "/api/v1/admin/hotels/" + hotelB + "/commission";
        String json = "{\"commissionBps\":300,\"reason\":\"Trying my luck\"}";
        putAs(null, url, json).andExpect(status().isUnauthorized());
        putAs(ownerB, url, json).andExpect(status().isForbidden());
        assertThat(settings.existsByHotelId(hotelB)).isFalse();
    }

    @Test
    void anAdminWhoseRoleWasRemovedCannotChangeTheRateEvenWithAValidToken() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("email").is("admin@example.com")),
                new Update().unset("platformRole"), User.class);

        putAs(admin, "/api/v1/admin/hotels/" + hotelB + "/commission", "{\"commissionBps\":300,\"reason\":\"Too late\"}")
                .andExpect(status().isForbidden());
        assertThat(settings.existsByHotelId(hotelB)).isFalse();
    }
}