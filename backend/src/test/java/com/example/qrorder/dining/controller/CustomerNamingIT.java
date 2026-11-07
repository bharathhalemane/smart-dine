package com.example.qrorder.dining.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.model.CustomerSession;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.repository.HotelRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class CustomerNamingIT extends AbstractIntegrationTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile("qr_customer=([^;]+)");

    @Autowired MockMvc mvc;
    @Autowired HotelRepository hotels;
    @Autowired DiningTableRepository tables;
    @Autowired QrCredentialRepository credentials;
    @Autowired DiningSessionRepository sessions;
    @Autowired CustomerSessionRepository customers;

    String hotelId;

    @BeforeEach
    void seed() {
        hotels.deleteAll();
        tables.deleteAll();
        credentials.deleteAll();
        sessions.deleteAll();
        customers.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        seedTable("T1", "tok-t1");
        seedTable("T2", "tok-t2");
    }

    private void seedTable(String number, String plainToken) {
        String id = tables.save(new DiningTable(hotelId, number, 4, null, Instant.now())).getId();
        credentials.save(new QrCredential(hotelId, id, Tokens.sha256(plainToken), "u", Instant.now()));
    }

    private MvcResult scan(String qrToken, String cookie) throws Exception {
        var request = post("/api/v1/scan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(qrToken));
        if (cookie != null) {
            request.cookie(new Cookie("qr_customer", cookie));
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    /** Scans as a brand-new diner and returns that diner's cookie token. */
    private String newDiner(String qrToken) throws Exception {
        String header = scan(qrToken, null).getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = COOKIE_VALUE.matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private ResultActions me(String cookie) throws Exception {
        return mvc.perform(get("/api/v1/customer/me").cookie(new Cookie("qr_customer", cookie)));
    }

    private ResultActions setName(String cookie, String json) throws Exception {
        return mvc.perform(put("/api/v1/customer/me/name").cookie(new Cookie("qr_customer", cookie))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void dinersAtOneTableAreNumberedInOrder() throws Exception {
        String first = newDiner("tok-t1");
        String second = newDiner("tok-t1");
        String third = newDiner("tok-t1");

        me(first).andExpect(jsonPath("$.guestNumber").value(1)).andExpect(jsonPath("$.label").value("Guest 1"));
        me(second).andExpect(jsonPath("$.guestNumber").value(2)).andExpect(jsonPath("$.label").value("Guest 2"));
        me(third).andExpect(jsonPath("$.guestNumber").value(3)).andExpect(jsonPath("$.displayName").isEmpty());
    }

    @Test
    void numberingIsPerTableVisitAndRestartsAfterTheVisitCloses() throws Exception {
        newDiner("tok-t1");
        newDiner("tok-t1");
        String atT2 = newDiner("tok-t2");
        me(atT2).andExpect(jsonPath("$.guestNumber").value(1));

        DiningSession visit = sessions.findAll().stream().filter(s -> s.getTableNumber().equals("T1")).findFirst()
                .orElseThrow();
        visit.close(Instant.now());
        sessions.save(visit);

        String nextVisit = newDiner("tok-t1");
        me(nextVisit).andExpect(jsonPath("$.guestNumber").value(1));
    }

    @Test
    void scanningAgainWithTheCookieDoesNotUseUpANumber() throws Exception {
        String first = newDiner("tok-t1");
        scan("tok-t1", first);
        scan("tok-t1", first);

        String second = newDiner("tok-t1");

        me(first).andExpect(jsonPath("$.guestNumber").value(1));
        me(second).andExpect(jsonPath("$.guestNumber").value(2));
    }

    @Test
    void aDinerCanSetChangeAndClearTheirName() throws Exception {
        String cookie = newDiner("tok-t1");

        setName(cookie, "{\"name\":\"  Anita   Rao \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Anita Rao"))
                .andExpect(jsonPath("$.label").value("Anita Rao"))
                .andExpect(jsonPath("$.guestNumber").value(1));
        me(cookie).andExpect(jsonPath("$.label").value("Anita Rao"));
        assertThat(customers.findAll().get(0).getDisplayName()).isEqualTo("Anita Rao");

        setName(cookie, "{\"name\":\"Anu\"}").andExpect(jsonPath("$.label").value("Anu"));

        setName(cookie, "{\"name\":\"  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").isEmpty())
                .andExpect(jsonPath("$.label").value("Guest 1"));
        setName(cookie, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Guest 1"));
    }

    @Test
    void unsafeNamesAreRejectedAndTheOldNameStays() throws Exception {
        String cookie = newDiner("tok-t1");
        setName(cookie, "{\"name\":\"Anita\"}").andExpect(status().isOk());

        for (String bad : List.of("<script>alert(1)</script>", "Smile \uD83D\uDE00", "x".repeat(31))) {
            setName(cookie, "{\"name\":\"%s\"}".formatted(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        me(cookie).andExpect(jsonPath("$.label").value("Anita"));
    }

    @Test
    void aDinerCanOnlyChangeTheirOwnName() throws Exception {
        String anita = newDiner("tok-t1");
        String ravi = newDiner("tok-t1");

        setName(anita, "{\"name\":\"Anita\"}").andExpect(status().isOk());

        me(ravi).andExpect(jsonPath("$.label").value("Guest 2"));
        assertThat(customers.findAll()).filteredOn(c -> c.getNumber() == 2).extracting(CustomerSession::getDisplayName)
                .containsOnlyNulls();
    }

    @Test
    void noCookieOrAClosedVisitCannotSetAName() throws Exception {
        mvc.perform(put("/api/v1/customer/me/name").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anita\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        String cookie = newDiner("tok-t1");
        DiningSession visit = sessions.findAll().get(0);
        visit.close(Instant.now());
        sessions.save(visit);

        setName(cookie, "{\"name\":\"Anita\"}")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
        assertThat(customers.findAll().get(0).getDisplayName()).isNull();
    }

    @Test
    void tenDinersArrivingTogetherGetTenDistinctNumbers() throws Exception {
        int diners = 10;
        ExecutorService pool = Executors.newFixedThreadPool(diners);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < diners; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return newDiner("tok-t1");
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sessions.count()).isEqualTo(1);
        Set<Integer> numbers = customers.findAll().stream().map(CustomerSession::getNumber)
                .collect(Collectors.toSet());
        assertThat(numbers).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, diners).boxed().toList());
    }
}