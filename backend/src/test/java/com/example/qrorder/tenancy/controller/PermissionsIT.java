package com.example.qrorder.tenancy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

class PermissionsIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired PasswordEncoder encoder;

    String hotelId;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
    }

    private void seedMember(String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
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

    private ResultActions myPermissions(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/hotels/" + hotelId + "/me/permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions getSettings(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/hotels/" + hotelId + "/staff-permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions patchSettings(String bearer, String json) throws Exception {
        return mvc.perform(patch("/api/v1/hotels/" + hotelId + "/staff-permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void aWaiterSeesOnlyTheirDefaultPermissions() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);

        myPermissions(login("waiter@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("WAITER"))
                .andExpect(jsonPath("$.permissions", hasItem("DELIVER_ORDERS")))
                .andExpect(jsonPath("$.permissions", not(hasItem("CLEAR_TABLE"))))
                .andExpect(jsonPath("$.permissions", not(hasItem("MANAGE_STAFF"))));
    }

    @Test
    void enablingTheSwitchGivesWaitersTheirNewPermissionOnTheNextRequest() throws Exception {
        seedMember("owner@example.com", HotelRole.OWNER);
        seedMember("waiter@example.com", HotelRole.WAITER);
        String waiterToken = login("waiter@example.com");

        myPermissions(waiterToken).andExpect(jsonPath("$.permissions", not(hasItem("CLEAR_TABLE"))));

        patchSettings(login("owner@example.com"), "{\"waitersMayClearTables\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitersMayClearTables").value(true))
                .andExpect(jsonPath("$.cashiersMayAdjustBills").value(false));

        // Same JWT as before: permissions come from the database, not from the token.
        myPermissions(waiterToken).andExpect(jsonPath("$.permissions", hasItem("CLEAR_TABLE")));
    }

    @Test
    void aPartialUpdateOnlyChangesTheFieldsSent() throws Exception {
        seedMember("owner@example.com", HotelRole.OWNER);
        String owner = login("owner@example.com");

        patchSettings(owner, "{\"waitersMayClearTables\":true}").andExpect(status().isOk());
        patchSettings(owner, "{\"cashiersMayAdjustBills\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitersMayClearTables").value(true))
                .andExpect(jsonPath("$.cashiersMayAdjustBills").value(true));

        Hotel saved = hotels.findById(hotelId).orElseThrow();
        assertThat(saved.isWaitersMayClearTables()).isTrue();
        assertThat(saved.isCashiersMayAdjustBills()).isTrue();
    }

    @Test
    void aManagerCanReadTheSettingsButOnlyTheOwnerCanChangeThem() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        getSettings(token).andExpect(status().isOk());
        patchSettings(token, "{\"waitersMayClearTables\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(hotels.findById(hotelId).orElseThrow().isWaitersMayClearTables()).isFalse();
    }

    @Test
    void aWaiterCannotReadTheSettings() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);

        getSettings(login("waiter@example.com")).andExpect(status().isForbidden());
    }

    @Test
    void aNonMemberGetsNotFound() throws Exception {
        User outsider = new User("out@example.com", encoder.encode(PASSWORD), "Out");
        outsider.setEmailVerified(true);
        users.save(outsider);

        myPermissions(login("out@example.com")).andExpect(status().isNotFound());
    }
}