package com.example.qrorder.tables.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class TableManagementIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        tables.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
    }

    private void seedMember(String hotel, String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private ResultActions createTable(String bearer, String hotel, String json) throws Exception {
        return call(post("/api/v1/hotels/" + hotel + "/tables"), bearer, json);
    }

    private String createdId(ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void aManagerCreatesATableWithANormalizedNumber() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);

        createTable(login("manager@example.com"), hotelId, "{\"number\":\"t1\",\"capacity\":4,\"section\":\"Terrace\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("T1"))
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.section").value("Terrace"))
                .andExpect(jsonPath("$.active").value(true));

        DiningTable saved = tables.findAll().get(0);
        assertThat(saved.getHotelId()).isEqualTo(hotelId);
        assertThat(saved.getNumber()).isEqualTo("T1");
    }

    @Test
    void aWaiterCanListAndReadButNotCreateOrChange() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        String id = createdId(createTable(login("manager@example.com"), hotelId, "{\"number\":\"T1\",\"capacity\":2}"));
        String waiter = login("waiter@example.com");

        call(get("/api/v1/hotels/" + hotelId + "/tables"), waiter, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        call(get("/api/v1/hotels/" + hotelId + "/tables/" + id), waiter, null).andExpect(status().isOk());

        createTable(waiter, hotelId, "{\"number\":\"T2\",\"capacity\":2}").andExpect(status().isForbidden());
        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + id), waiter, "{\"capacity\":9}")
                .andExpect(status().isForbidden());
        call(delete("/api/v1/hotels/" + hotelId + "/tables/" + id), waiter, null).andExpect(status().isForbidden());
        assertThat(tables.count()).isEqualTo(1);
    }

    @Test
    void numbersAreUniquePerHotelNotAcrossHotels() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        seedMember(otherHotelId, "other@example.com", HotelRole.MANAGER);

        createTable(login("manager@example.com"), hotelId, "{\"number\":\"T1\",\"capacity\":4}")
                .andExpect(status().isCreated());
        createTable(login("manager@example.com"), hotelId, "{\"number\":\" t1 \",\"capacity\":2}")
                .andExpect(status().isBadRequest()); // leading space is rejected by validation
        createTable(login("manager@example.com"), hotelId, "{\"number\":\"t1\",\"capacity\":2}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        createTable(login("other@example.com"), otherHotelId, "{\"number\":\"T1\",\"capacity\":4}")
                .andExpect(status().isCreated()); // same number in another hotel is fine
        assertThat(tables.count()).isEqualTo(2);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        createTable(token, hotelId, "{\"number\":\"T1\",\"capacity\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        createTable(token, hotelId, "{\"number\":\"T1\",\"capacity\":99}").andExpect(status().isBadRequest());
        createTable(token, hotelId, "{\"number\":\"T#1!\",\"capacity\":4}").andExpect(status().isBadRequest());
        createTable(token, hotelId, "{\"capacity\":4}").andExpect(status().isBadRequest());
        assertThat(tables.count()).isZero();
    }

    @Test
    void updatingChangesOnlyTheFieldsSentAndRejectsATakenNumber() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        String t1 = createdId(createTable(token, hotelId, "{\"number\":\"T1\",\"capacity\":4,\"section\":\"Terrace\"}"));
        createdId(createTable(token, hotelId, "{\"number\":\"T2\",\"capacity\":2}"));

        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + t1), token, "{\"capacity\":6,\"section\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("T1"))
                .andExpect(jsonPath("$.capacity").value(6))
                .andExpect(jsonPath("$.section").doesNotExist());

        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + t1), token, "{\"number\":\"t2\"}")
                .andExpect(status().isConflict());
        assertThat(tables.findByIdAndHotelId(t1, hotelId).orElseThrow().getNumber()).isEqualTo("T1");
    }

    @Test
    void deleteDeactivatesAndInactiveTablesAreHiddenUnlessAManagerAsks() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        String manager = login("manager@example.com");
        String waiter = login("waiter@example.com");
        createdId(createTable(manager, hotelId, "{\"number\":\"T10\",\"capacity\":4}"));
        createdId(createTable(manager, hotelId, "{\"number\":\"T2\",\"capacity\":4}"));
        String t1 = createdId(createTable(manager, hotelId, "{\"number\":\"T1\",\"capacity\":4}"));

        call(delete("/api/v1/hotels/" + hotelId + "/tables/" + t1), manager, null).andExpect(status().isNoContent());

        assertThat(tables.findByIdAndHotelId(t1, hotelId).orElseThrow().isActive()).isFalse();
        call(get("/api/v1/hotels/" + hotelId + "/tables"), waiter, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].number").value("T2"))   // natural order: T2 before T10
                .andExpect(jsonPath("$[1].number").value("T10"));
        call(get("/api/v1/hotels/" + hotelId + "/tables?includeInactive=true"), manager, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        call(get("/api/v1/hotels/" + hotelId + "/tables?includeInactive=true"), waiter, null)
                .andExpect(status().isForbidden());

        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + t1), manager, "{\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void aTableFromAnotherHotelIsNotFoundAndStaysUntouched() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        seedMember(otherHotelId, "other@example.com", HotelRole.MANAGER);
        String foreignId = createdId(createTable(login("other@example.com"), otherHotelId,
                "{\"number\":\"T1\",\"capacity\":4}"));
        String token = login("manager@example.com");

        call(get("/api/v1/hotels/" + hotelId + "/tables/" + foreignId), token, null).andExpect(status().isNotFound());
        call(patch("/api/v1/hotels/" + hotelId + "/tables/" + foreignId), token, "{\"capacity\":9}")
                .andExpect(status().isNotFound());
        call(delete("/api/v1/hotels/" + hotelId + "/tables/" + foreignId), token, null)
                .andExpect(status().isNotFound());

        DiningTable untouched = tables.findByIdAndHotelId(foreignId, otherHotelId).orElseThrow();
        assertThat(untouched.getCapacity()).isEqualTo(4);
        assertThat(untouched.isActive()).isTrue();
    }
}