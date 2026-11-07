package com.example.qrorder.menu.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuItemRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class MenuAvailabilityIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired MenuItemRepository items;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String paneer;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        items.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        paneer = items.save(new MenuItem(hotelId, "cat-1", "Paneer Tikka", null, 28000L, Diet.VEG, 0, Instant.now()))
                .getId();
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

    private ResultActions setAvailable(String bearer, String itemId, String json) throws Exception {
        return mvc.perform(put("/api/v1/hotels/" + hotelId + "/menu/items/" + itemId + "/availability")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private boolean storedAvailable(String itemId) {
        return items.findById(itemId).orElseThrow().isAvailable();
    }

    @Test
    void kitchenManagerAndOwnerCanMarkAnItemUnavailableAndAvailableAgain() throws Exception {
        seedMember("cook@example.com", HotelRole.KITCHEN);
        seedMember("manager@example.com", HotelRole.MANAGER);
        seedMember("owner@example.com", HotelRole.OWNER);

        setAvailable(login("cook@example.com"), paneer, "{\"available\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        assertThat(storedAvailable(paneer)).isFalse();

        setAvailable(login("manager@example.com"), paneer, "{\"available\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
        assertThat(storedAvailable(paneer)).isTrue();

        setAvailable(login("owner@example.com"), paneer, "{\"available\":false}").andExpect(status().isOk());
        assertThat(storedAvailable(paneer)).isFalse();
    }

    @Test
    void repeatingTheSameValueIsHarmless() throws Exception {
        seedMember("cook@example.com", HotelRole.KITCHEN);
        String token = login("cook@example.com");

        for (int i = 0; i < 3; i++) {
            setAvailable(token, paneer, "{\"available\":false}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        }
        assertThat(storedAvailable(paneer)).isFalse();
    }

    @Test
    void waitersAndCashiersCannotToggle() throws Exception {
        seedMember("waiter@example.com", HotelRole.WAITER);
        seedMember("cashier@example.com", HotelRole.CASHIER);

        for (String email : List.of("waiter@example.com", "cashier@example.com")) {
            setAvailable(login(email), paneer, "{\"available\":false}").andExpect(status().isForbidden());
        }
        assertThat(storedAvailable(paneer)).isTrue();
    }

    @Test
    void aMissingValueIsRejected() throws Exception {
        seedMember("cook@example.com", HotelRole.KITCHEN);
        String token = login("cook@example.com");

        setAvailable(token, paneer, "{}").andExpect(status().isBadRequest());
        assertThat(storedAvailable(paneer)).isTrue();
    }

    @Test
    void foreignAndDeactivatedItemsAreNotFound() throws Exception {
        seedMember("cook@example.com", HotelRole.KITCHEN);
        String foreign = items.save(new MenuItem(otherHotelId, "cat-9", "Lassi", null, 6000L, Diet.VEG, 0,
                Instant.now())).getId();
        MenuItem removed = new MenuItem(hotelId, "cat-1", "Old dish", null, 9000L, Diet.VEG, 1, Instant.now());
        removed.setActive(false);
        String removedId = items.save(removed).getId();
        String token = login("cook@example.com");

        setAvailable(token, foreign, "{\"available\":false}").andExpect(status().isNotFound());
        setAvailable(token, removedId, "{\"available\":false}").andExpect(status().isNotFound());
        assertThat(storedAvailable(foreign)).isTrue();
        assertThat(storedAvailable(removedId)).isTrue();
    }
}