package com.example.qrorder.tenancy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.example.qrorder.tenancy.model.MembershipStatus;
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

class StaffManagementIT extends AbstractIntegrationTest {

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

    private User seedMember(String hotel, String email, HotelRole... roles) {
        User user = new User(email, encoder.encode(PASSWORD), email.substring(0, email.indexOf('@')));
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(roles), user.getId(), Instant.now()));
        return user;
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions list(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/hotels/" + hotelId + "/staff")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions changeRoles(String bearer, String userId, String rolesJson) throws Exception {
        return mvc.perform(patch("/api/v1/hotels/" + hotelId + "/staff/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":%s}".formatted(rolesJson)));
    }

    private ResultActions remove(String bearer, String userId) throws Exception {
        return mvc.perform(delete("/api/v1/hotels/" + hotelId + "/staff/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private Set<HotelRole> rolesOf(User user) {
        return memberships.findByHotelIdAndUserId(hotelId, user.getId()).orElseThrow().getRoles();
    }

    private MembershipStatus statusOf(User user) {
        return memberships.findByHotelIdAndUserId(hotelId, user.getId()).orElseThrow().getStatus();
    }

    @Test
    void ownerListsActiveStaffWithoutSecrets() throws Exception {
        seedMember(hotelId, "owner@example.com", HotelRole.OWNER);
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        User gone = seedMember(hotelId, "gone@example.com", HotelRole.KITCHEN);
        HotelMembership m = memberships.findByHotelIdAndUserId(hotelId, gone.getId()).orElseThrow();
        m.setStatus(MembershipStatus.REMOVED);
        memberships.save(m);

        list(login("owner@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].email", containsInAnyOrder("owner@example.com", "waiter@example.com")))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void aWaiterCannotListStaff() throws Exception {
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);

        list(login("waiter@example.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void ownerChangesRoles() throws Exception {
        seedMember(hotelId, "owner@example.com", HotelRole.OWNER);
        User waiter = seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);

        changeRoles(login("owner@example.com"), waiter.getId(), "[\"CASHIER\",\"KITCHEN\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("waiter@example.com"))
                .andExpect(jsonPath("$.roles.length()").value(2));

        assertThat(rolesOf(waiter)).containsExactlyInAnyOrder(HotelRole.CASHIER, HotelRole.KITCHEN);
    }

    @Test
    void aManagerManagesOrdinaryStaffButNotManagersOrOwners() throws Exception {
        User owner = seedMember(hotelId, "owner@example.com", HotelRole.OWNER);
        User manager = seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        User other = seedMember(hotelId, "manager2@example.com", HotelRole.MANAGER);
        User waiter = seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        String token = login("manager@example.com");

        changeRoles(token, waiter.getId(), "[\"KITCHEN\"]").andExpect(status().isOk());
        changeRoles(token, waiter.getId(), "[\"MANAGER\"]").andExpect(status().isForbidden());
        changeRoles(token, other.getId(), "[\"WAITER\"]").andExpect(status().isForbidden());
        changeRoles(token, owner.getId(), "[\"WAITER\"]").andExpect(status().isForbidden());
        remove(token, other.getId()).andExpect(status().isForbidden());

        assertThat(rolesOf(waiter)).containsExactly(HotelRole.KITCHEN);
        assertThat(rolesOf(other)).containsExactly(HotelRole.MANAGER);
        assertThat(rolesOf(owner)).containsExactly(HotelRole.OWNER);
        assertThat(rolesOf(manager)).containsExactly(HotelRole.MANAGER);
    }

    @Test
    void theOwnerRoleCannotBeGrantedHere() throws Exception {
        seedMember(hotelId, "owner@example.com", HotelRole.OWNER);
        User waiter = seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);

        changeRoles(login("owner@example.com"), waiter.getId(), "[\"OWNER\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rolesOf(waiter)).containsExactly(HotelRole.WAITER);
    }

    @Test
    void nobodyCanChangeTheirOwnRoles() throws Exception {
        User manager = seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);

        changeRoles(login("manager@example.com"), manager.getId(), "[\"WAITER\"]")
                .andExpect(status().isForbidden());
        assertThat(rolesOf(manager)).containsExactly(HotelRole.MANAGER);
    }

    @Test
    void removedStaffLoseAccessOnTheirNextRequest() throws Exception {
        seedMember(hotelId, "owner@example.com", HotelRole.OWNER);
        User waiter = seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        String waiterToken = login("waiter@example.com");

        mvc.perform(get("/api/v1/hotels/" + hotelId).header(HttpHeaders.AUTHORIZATION, "Bearer " + waiterToken))
                .andExpect(status().isOk());

        remove(login("owner@example.com"), waiter.getId()).andExpect(status().isNoContent());

        assertThat(statusOf(waiter)).isEqualTo(MembershipStatus.REMOVED);
        // The old JWT is still valid, but the hotel now looks like it does not exist for this user.
        mvc.perform(get("/api/v1/hotels/" + hotelId).header(HttpHeaders.AUTHORIZATION, "Bearer " + waiterToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void theOnlyOwnerCannotLeaveOrBeDemoted() throws Exception {
        User owner = seedMember(hotelId, "owner@example.com", HotelRole.OWNER);

        remove(login("owner@example.com"), owner.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(statusOf(owner)).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(rolesOf(owner)).containsExactly(HotelRole.OWNER);
    }

    @Test
    void withTwoOwnersOneCanGoButNotTheLast() throws Exception {
        User a = seedMember(hotelId, "a@example.com", HotelRole.OWNER);
        User b = seedMember(hotelId, "b@example.com", HotelRole.OWNER);
        String tokenA = login("a@example.com");

        remove(tokenA, b.getId()).andExpect(status().isNoContent());
        remove(tokenA, a.getId()).andExpect(status().isConflict());

        assertThat(statusOf(b)).isEqualTo(MembershipStatus.REMOVED);
        assertThat(statusOf(a)).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void aUserFromAnotherHotelIsNotFound() throws Exception {
        seedMember(hotelId, "owner@example.com", HotelRole.OWNER);
        String otherHotel = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        User stranger = seedMember(otherHotel, "stranger@example.com", HotelRole.WAITER);
        String token = login("owner@example.com");

        changeRoles(token, stranger.getId(), "[\"KITCHEN\"]").andExpect(status().isNotFound());
        remove(token, stranger.getId()).andExpect(status().isNotFound());

        assertThat(memberships.findByHotelIdAndUserId(otherHotel, stranger.getId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACTIVE);
    }
}