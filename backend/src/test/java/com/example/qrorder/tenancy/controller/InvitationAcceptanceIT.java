package com.example.qrorder.tenancy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.InvitationStatus;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.model.StaffInvitation;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.example.qrorder.tenancy.repository.StaffInvitationRepository;
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

class InvitationAcceptanceIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String TOKEN = "invite-token-for-tests";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired StaffInvitationRepository invitations;
    @Autowired PasswordEncoder encoder;

    String hotelId;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        invitations.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "owner-id", null,
                "Asia/Kolkata", "INR", Instant.now())).getId();
    }

    private User seedUser(String email, boolean verified) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
        user.setEmailVerified(verified);
        return users.save(user);
    }

    private void seedInvitation(String email, HotelRole role, long secondsToLive) {
        Instant now = Instant.now();
        invitations.save(new StaffInvitation(hotelId, email, Set.of(role), Tokens.sha256(TOKEN),
                "owner-id", now.plusSeconds(secondsToLive), now));
    }

    private String loginToken(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions send(String path, String bearer, String json) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions accept(String bearer) throws Exception {
        return send("/api/v1/invitations/accept", bearer, "{\"token\":\"%s\"}".formatted(TOKEN));
    }

    private ResultActions acceptNew(String password) throws Exception {
        return send("/api/v1/invitations/accept-new", null,
                "{\"token\":\"%s\",\"name\":\"New Chef\",\"password\":\"%s\"}".formatted(TOKEN, password));
    }

    private InvitationStatus invitationStatus() {
        return invitations.findAll().get(0).getStatus();
    }

    @Test
    void existingUserAcceptsAndTheTokenWorksOnlyOnce() throws Exception {
        User chef = seedUser("chef@example.com", true);
        seedInvitation("chef@example.com", HotelRole.KITCHEN, 3600);
        String bearer = loginToken("chef@example.com");

        accept(bearer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelName").value("Spice Garden"))
                .andExpect(jsonPath("$.roles[0]").value("KITCHEN"));

        HotelMembership membership = memberships.findByHotelIdAndUserId(hotelId, chef.getId()).orElseThrow();
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membership.getRoles()).containsExactly(HotelRole.KITCHEN);
        assertThat(invitationStatus()).isEqualTo(InvitationStatus.ACCEPTED);

        accept(bearer).andExpect(status().isNotFound()); // already used
    }

    @Test
    void aDifferentAccountCannotUseTheInvitationAndDoesNotBurnIt() throws Exception {
        seedUser("someone-else@example.com", true);
        seedInvitation("chef@example.com", HotelRole.KITCHEN, 3600);

        accept(loginToken("someone-else@example.com")).andExpect(status().isNotFound());

        assertThat(invitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(memberships.count()).isZero();
    }

    @Test
    void anUnverifiedAccountCannotAccept() throws Exception {
        seedUser("chef@example.com", false);
        seedInvitation("chef@example.com", HotelRole.KITCHEN, 3600);

        accept(loginToken("chef@example.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(invitationStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void anExpiredInvitationIsRejected() throws Exception {
        seedUser("chef@example.com", true);
        seedInvitation("chef@example.com", HotelRole.KITCHEN, -60);

        accept(loginToken("chef@example.com")).andExpect(status().isNotFound());
        assertThat(memberships.count()).isZero();
    }

    @Test
    void aNewPersonCreatesAVerifiedAccountAndJoins() throws Exception {
        seedInvitation("new.chef@example.com", HotelRole.WAITER, 3600);

        acceptNew(PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hotelId").value(hotelId))
                .andExpect(jsonPath("$.roles[0]").value("WAITER"));

        User created = users.findByEmail("new.chef@example.com").orElseThrow();
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getPasswordHash()).startsWith("$2");
        assertThat(memberships.findByHotelIdAndUserId(hotelId, created.getId())).isPresent();
        assertThat(invitationStatus()).isEqualTo(InvitationStatus.ACCEPTED);

        loginToken("new.chef@example.com"); // can sign in straight away (asserts 200)
    }

    @Test
    void newAccountFlowFailsCleanlyWhenTheAccountAlreadyExists() throws Exception {
        seedUser("chef@example.com", true);
        seedInvitation("chef@example.com", HotelRole.KITCHEN, 3600);

        acceptNew(PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(invitationStatus()).isEqualTo(InvitationStatus.PENDING); // transaction rolled back
        assertThat(memberships.count()).isZero();
    }

    @Test
    void aWeakPasswordDoesNotBurnTheInvitation() throws Exception {
        seedInvitation("new.chef@example.com", HotelRole.WAITER, 3600);

        acceptNew("short").andExpect(status().isBadRequest());

        assertThat(invitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(users.count()).isZero();
    }

    @Test
    void previewTellsTheClientWhichFlowToShowAndRejectsBadTokens() throws Exception {
        seedUser("chef@example.com", true);
        seedInvitation("chef@example.com", HotelRole.KITCHEN, 3600);

        send("/api/v1/invitations/preview", null, "{\"token\":\"%s\"}".formatted(TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelName").value("Spice Garden"))
                .andExpect(jsonPath("$.email").value("chef@example.com"))
                .andExpect(jsonPath("$.accountExists").value(true));

        send("/api/v1/invitations/preview", null, "{\"token\":\"nope\"}").andExpect(status().isNotFound());
        assertThat(invitationStatus()).isEqualTo(InvitationStatus.PENDING); // preview never consumes
    }

    @Test
    void aPreviouslyRemovedMemberRejoinsWithTheNewRoles() throws Exception {
        User chef = seedUser("chef@example.com", true);
        HotelMembership old = memberships.save(new HotelMembership(hotelId, chef.getId(),
                Set.of(HotelRole.WAITER), "owner-id", Instant.now()));
        old.setStatus(MembershipStatus.REMOVED);
        memberships.save(old);
        seedInvitation("chef@example.com", HotelRole.CASHIER, 3600);

        accept(loginToken("chef@example.com")).andExpect(status().isOk());

        HotelMembership rejoined = memberships.findByHotelIdAndUserId(hotelId, chef.getId()).orElseThrow();
        assertThat(rejoined.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(rejoined.getRoles()).containsExactly(HotelRole.CASHIER);
        assertThat(memberships.count()).isEqualTo(1);
    }
}