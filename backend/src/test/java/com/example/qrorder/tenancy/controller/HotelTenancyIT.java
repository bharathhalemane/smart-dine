package com.example.qrorder.tenancy.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class HotelTenancyIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
    }

    private User seedUser(String email, boolean verified) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
        user.setEmailVerified(verified);
        return users.save(user);
    }

    private String tokenFor(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions createHotel(String token, String name) throws Exception {
        return mvc.perform(post("/api/v1/hotels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\"}".formatted(name)));
    }

    private String createHotelId(String token, String name) throws Exception {
        MvcResult result = createHotel(token, name).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private ResultActions getHotel(String token, String hotelId) throws Exception {
        return mvc.perform(get("/api/v1/hotels/" + hotelId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions patchHotel(String token, String hotelId, String json) throws Exception {
        return mvc.perform(patch("/api/v1/hotels/" + hotelId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void aVerifiedUserCreatesAHotelAndBecomesItsOwner() throws Exception {
        User owner = seedUser("a@example.com", true);
        String token = tokenFor("a@example.com");

        createHotel(token, "Spice Garden")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("spice-garden"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"));

        HotelMembership membership = memberships.findAll().get(0);
        assertThat(membership.getUserId()).isEqualTo(owner.getId());
        assertThat(membership.getRoles()).containsExactly(HotelRole.OWNER);

        mvc.perform(get("/api/v1/hotels").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hotel.name").value("Spice Garden"))
                .andExpect(jsonPath("$[0].roles[0]").value("OWNER"));
    }

    @Test
    void anUnverifiedUserCannotCreateAHotel() throws Exception {
        seedUser("u@example.com", false);

        createHotel(tokenFor("u@example.com"), "Nope Hotel")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(hotels.count()).isZero();
        assertThat(memberships.count()).isZero();
    }

    @Test
    void hotelsWithTheSameNameGetDistinctSlugs() throws Exception {
        seedUser("a@example.com", true);
        seedUser("b@example.com", true);

        createHotel(tokenFor("a@example.com"), "Spice Garden")
                .andExpect(jsonPath("$.slug").value("spice-garden"));
        createHotel(tokenFor("b@example.com"), "Spice Garden")
                .andExpect(jsonPath("$.slug").value(Matchers.matchesPattern("spice-garden-\\d{4}")));
    }

    @Test
    void oneHotelCannotReachAnotherHotelsData() throws Exception {
        seedUser("a@example.com", true);
        seedUser("b@example.com", true);
        String tokenA = tokenFor("a@example.com");
        String tokenB = tokenFor("b@example.com");
        String hotelA = createHotelId(tokenA, "Hotel A");
        String hotelB = createHotelId(tokenB, "Hotel B");

        getHotel(tokenA, hotelA).andExpect(status().isOk());

        // Another tenant's hotel looks exactly like a hotel that does not exist.
        getHotel(tokenA, hotelB)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Hotel not found"));
        getHotel(tokenA, "does-not-exist")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Hotel not found"));

        patchHotel(tokenA, hotelB, "{\"name\":\"Hacked\"}").andExpect(status().isNotFound());
        assertThat(hotels.findById(hotelB).orElseThrow().getName()).isEqualTo("Hotel B");

        mvc.perform(get("/api/v1/hotels").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hotel.id").value(hotelA));
    }

    @Test
    void rolesDecideWhatAMemberMayDo() throws Exception {
        User owner = seedUser("owner@example.com", true);
        User manager = seedUser("manager@example.com", true);
        String ownerToken = tokenFor("owner@example.com");
        String hotelId = createHotelId(ownerToken, "Role Hotel");
        memberships.save(new HotelMembership(hotelId, manager.getId(), Set.of(HotelRole.MANAGER),
                owner.getId(), Instant.now()));
        String managerToken = tokenFor("manager@example.com");

        getHotel(managerToken, hotelId).andExpect(status().isOk());
        patchHotel(managerToken, hotelId, "{\"name\":\"Nope\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        patchHotel(ownerToken, hotelId, "{\"name\":\"Renamed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.slug").value("role-hotel")); // slug is stable
    }

    @Test
    void aSuspendedHotelIsClosedToItsOwnStaff() throws Exception {
        seedUser("a@example.com", true);
        String token = tokenFor("a@example.com");
        String hotelId = createHotelId(token, "Closing Hotel");

        Hotel hotel = hotels.findById(hotelId).orElseThrow();
        hotel.setStatus(HotelStatus.SUSPENDED);
        hotels.save(hotel);

        getHotel(token, hotelId).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/hotels").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$[0].hotel.status").value("SUSPENDED")); // still listed so the UI can explain
    }

    @Test
    void aRemovedMembershipLooksLikeNoHotelAtAll() throws Exception {
        User owner = seedUser("a@example.com", true);
        String token = tokenFor("a@example.com");
        String hotelId = createHotelId(token, "Left Hotel");

        HotelMembership membership = memberships.findByHotelIdAndUserId(hotelId, owner.getId()).orElseThrow();
        membership.setStatus(MembershipStatus.REMOVED);
        memberships.save(membership);

        getHotel(token, hotelId).andExpect(status().isNotFound());
    }

    @Test
    void anUnknownTimezoneIsRejected() throws Exception {
        seedUser("a@example.com", true);
        String token = tokenFor("a@example.com");
        String hotelId = createHotelId(token, "Zone Hotel");

        patchHotel(token, hotelId, "{\"timezone\":\"Mars/Base\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void hotelEndpointsNeedALogin() throws Exception {
        mvc.perform(get("/api/v1/hotels")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/hotels/anything")).andExpect(status().isUnauthorized());
    }
}