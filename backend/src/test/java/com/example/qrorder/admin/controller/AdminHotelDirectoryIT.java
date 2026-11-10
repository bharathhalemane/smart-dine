package com.example.qrorder.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class AdminHotelDirectoryIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String BASE = "/api/v1/admin/hotels";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired PasswordEncoder encoder;

    Instant base;
    String spiceId;
    String lotusId;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        base = Instant.now().minus(Duration.ofDays(1));

        User admin = account("admin@example.com", "Platform Admin");
        admin.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        users.save(admin);
        User anita = users.save(account("anita@spice.example", "Anita"));
        User bala = users.save(account("bala@lotus.example", "Bala"));
        users.save(account("regular@example.com", "Regular Staff"));

        Hotel spice = new Hotel("spice-garden", "Spice Garden", anita.getId(), "MG Road Bengaluru",
                "Asia/Kolkata", "INR", base);
        spiceId = hotels.save(spice).getId();

        Hotel lotus = new Hotel("lotus-cafe", "Lotus Cafe", bala.getId(), "Indiranagar Bengaluru",
                "Asia/Kolkata", "INR", base.minus(Duration.ofMinutes(1)));
        lotus.setStatus(HotelStatus.SUSPENDED);
        lotus.setStatusReason("Unpaid invoice");
        lotusId = hotels.save(lotus).getId();

        Hotel dhaba = new Hotel("old-dhaba", "Old Dhaba", anita.getId(), "Mysuru", "Asia/Kolkata", "INR",
                base.minus(Duration.ofMinutes(2)));
        dhaba.setStatus(HotelStatus.DEACTIVATED);
        hotels.save(dhaba);

        for (int i = 1; i <= 22; i++) {
            hotels.save(new Hotel("filler-%02d".formatted(i), "Filler %02d".formatted(i), anita.getId(),
                    "Somewhere", "Asia/Kolkata", "INR", base.minus(Duration.ofMinutes(2 + i))));
        }

        // Spice Garden: three active staff and one removed; two tables
        staff(spiceId, anita, MembershipStatus.ACTIVE);
        staff(spiceId, users.save(account("s1@example.com", "S1")), MembershipStatus.ACTIVE);
        staff(spiceId, users.save(account("s2@example.com", "S2")), MembershipStatus.ACTIVE);
        staff(spiceId, users.save(account("gone@example.com", "Gone")), MembershipStatus.REMOVED);
        tables.save(new DiningTable(spiceId, "T1", 4, null, base));
        tables.save(new DiningTable(spiceId, "T2", 4, null, base));
    }

    // ---------- helpers ----------

    private User account(String email, String name) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        return user;
    }

    private void staff(String hotelId, User user, MembershipStatus status) {
        HotelMembership membership = new HotelMembership(hotelId, user.getId(), Set.of(HotelRole.WAITER),
                user.getId(), Instant.now());
        membership.setStatus(status);
        memberships.save(membership);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions asAdmin(String query) throws Exception {
        return mvc.perform(get(BASE + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + login("admin@example.com")));
    }

    private List<String> idsOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.items[*].id");
    }

    // ---------- who may use it ----------

    @Test
    void onlyPlatformAdminsCanUseTheDirectory() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + login("regular@example.com")))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + spiceId).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + login("anita@spice.example"))).andExpect(status().isForbidden()); // even the hotel's owner
        asAdmin("").andExpect(status().isOk());
    }

    // ---------- paging and sorting ----------

    @Test
    void theDefaultPageIsNewestFirstAndSaysHowManyThereAre() throws Exception {
        asAdmin("").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].slug").value("spice-garden"))
                .andExpect(jsonPath("$.items[1].slug").value("lotus-cafe"))
                .andExpect(jsonPath("$.items[2].slug").value("old-dhaba"));
    }

    @Test
    void pagesDoNotOverlapAndTogetherCoverEveryHotel() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            List<String> ids = idsOf(asAdmin("?size=10&page=" + page).andExpect(status().isOk()));
            assertThat(ids).hasSize(page < 2 ? 10 : 5);
            assertThat(seen).doesNotContainAnyElementsOf(ids);
            seen.addAll(ids);
        }
        assertThat(seen).hasSize(25);
        asAdmin("?size=10&page=5").andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(25));
    }

    @Test
    void sortingByNameWorksBothWays() throws Exception {
        asAdmin("?sort=name,asc").andExpect(jsonPath("$.items[0].name").value("Filler 01"));
        asAdmin("?sort=name,desc").andExpect(jsonPath("$.items[0].name").value("Spice Garden"));
        asAdmin("?sort=createdAt,asc").andExpect(jsonPath("$.items[0].slug").value("filler-22"));
    }

    // ---------- filters and search ----------

    @Test
    void filteringByStatus() throws Exception {
        asAdmin("?status=SUSPENDED").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("lotus-cafe"))
                .andExpect(jsonPath("$.items[0].statusReason").value("Unpaid invoice"));
        asAdmin("?status=DEACTIVATED").andExpect(jsonPath("$.totalElements").value(1));
        asAdmin("?status=ACTIVE").andExpect(jsonPath("$.totalElements").value(23));
    }

    @Test
    void filteringByCreationTime() throws Exception {
        String after = base.minus(Duration.ofSeconds(90)).toString();
        asAdmin("?createdAfter=" + after).andExpect(jsonPath("$.totalElements").value(2)); // spice and lotus
        asAdmin("?createdAfter=" + after + "&createdBefore=" + base.minus(Duration.ofSeconds(30)))
                .andExpect(jsonPath("$.totalElements").value(1)); // lotus only
    }

    @Test
    void searchMatchesNameSlugAddressOwnerEmailAndExactId() throws Exception {
        asAdmin("?q=GARDEN").andExpect(jsonPath("$.totalElements").value(1));         // name, case-insensitive
        asAdmin("?q=@spice.example").andExpect(jsonPath("$.totalElements").value(24)); // owner's email: all her hotels
        asAdmin("?q=LOTUS-CAFE").andExpect(jsonPath("$.totalElements").value(1));     // slug
        asAdmin("?q=bengaluru").andExpect(jsonPath("$.totalElements").value(2));      // address
        asAdmin("?q=bala@").andExpect(jsonPath("$.totalElements").value(1))           // owner's email
                .andExpect(jsonPath("$.items[0].slug").value("lotus-cafe"));
        asAdmin("?q=" + spiceId).andExpect(jsonPath("$.totalElements").value(1));     // exact id
        asAdmin("?q=nothing-like-this").andExpect(jsonPath("$.totalElements").value(0));
        asAdmin("?q=spice&status=SUSPENDED").andExpect(jsonPath("$.totalElements").value(0)); // filters combine
    }

    @Test
    void searchTextIsNeverTreatedAsAPattern() throws Exception {
        asAdmin("?q=.*").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        asAdmin("?q=(").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        asAdmin("?q=%5B").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0)); // "["
    }

    // ---------- what a row contains ----------

    @Test
    void aRowShowsTheOwnerAndHowBigTheHotelIs() throws Exception {
        asAdmin("?q=spice-garden").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].owner.name").value("Anita"))
                .andExpect(jsonPath("$.items[0].owner.email").value("anita@spice.example"))
                .andExpect(jsonPath("$.items[0].staffCount").value(3)) // the removed member is not counted
                .andExpect(jsonPath("$.items[0].tableCount").value(2))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
        asAdmin("?q=lotus-cafe").andExpect(jsonPath("$.items[0].staffCount").value(0))
                .andExpect(jsonPath("$.items[0].tableCount").value(0));
    }

    @Test
    void theDetailShowsTheSettingsToo() throws Exception {
        mvc.perform(get(BASE + "/" + spiceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + login("admin@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("spice-garden"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.waitersMayClearTables").value(false))
                .andExpect(jsonPath("$.cashiersMayAdjustBills").value(false))
                .andExpect(jsonPath("$.staffCount").value(3))
                .andExpect(jsonPath("$.owner.email").value("anita@spice.example"));
        mvc.perform(get(BASE + "/no-such-hotel").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + login("admin@example.com"))).andExpect(status().isNotFound());
    }

    // ---------- bad input ----------

    @Test
    void badParametersAreRefusedWith400() throws Exception {
        asAdmin("?size=0").andExpect(status().isBadRequest());
        asAdmin("?size=101").andExpect(status().isBadRequest());
        asAdmin("?page=-1").andExpect(status().isBadRequest());
        asAdmin("?sort=passwordHash").andExpect(status().isBadRequest());
        asAdmin("?sort=name,sideways").andExpect(status().isBadRequest());
        asAdmin("?status=BOGUS").andExpect(status().isBadRequest());
        asAdmin("?createdAfter=yesterday").andExpect(status().isBadRequest());
        asAdmin("?q=" + "x".repeat(101)).andExpect(status().isBadRequest());
    }
}