package com.example.qrorder.tenancy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.common.mail.EmailSender;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.InvitationStatus;
import com.example.qrorder.tenancy.model.StaffInvitation;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.example.qrorder.tenancy.repository.StaffInvitationRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class StaffInvitationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired StaffInvitationRepository invitations;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailSender emailSender; // the mock from AbstractIntegrationTest

    String hotelId;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        invitations.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
    }

    /** Creates a verified user who is a member of the hotel with the given role, and returns an access token. */
    private String memberToken(String email, HotelRole role) throws Exception {
        User user = new User(email, encoder.encode(PASSWORD), "Test " + role);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotelId, user.getId(), Set.of(role), user.getId(), Instant.now()));
        return login(email);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions invite(String token, String email, String rolesJson) throws Exception {
        return mvc.perform(post("/api/v1/hotels/" + hotelId + "/invitations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"roles\":%s}".formatted(email, rolesJson)));
    }

    @Test
    void ownerInvitesStaffAndOnlyTheHashIsStored() throws Exception {
        String token = memberToken("owner@example.com", HotelRole.OWNER);

        invite(token, "Chef@Example.com", "[\"KITCHEN\"]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("chef@example.com"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roles[0]").value("KITCHEN"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("chef@example.com"), anyString(), body.capture());
        Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body.getValue());
        assertThat(m.find()).isTrue();

        StaffInvitation stored = invitations.findAll().get(0);
        assertThat(stored.getTokenHash()).isEqualTo(Tokens.sha256(m.group(1))).isNotEqualTo(m.group(1));
        assertThat(stored.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void aWaiterCannotInvite() throws Exception {
        String token = memberToken("waiter@example.com", HotelRole.WAITER);

        invite(token, "new@example.com", "[\"WAITER\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(invitations.count()).isZero();
    }

    @Test
    void aManagerCanInviteWaitersButNotManagers() throws Exception {
        String token = memberToken("manager@example.com", HotelRole.MANAGER);

        invite(token, "w@example.com", "[\"WAITER\"]").andExpect(status().isCreated());
        invite(token, "m@example.com", "[\"MANAGER\"]").andExpect(status().isForbidden());
        assertThat(invitations.count()).isEqualTo(1);
    }

    @Test
    void theOwnerRoleCannotBeInvited() throws Exception {
        String token = memberToken("owner@example.com", HotelRole.OWNER);

        invite(token, "x@example.com", "[\"OWNER\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invitingAnExistingMemberIsRejected() throws Exception {
        String token = memberToken("owner@example.com", HotelRole.OWNER);
        memberToken("cook@example.com", HotelRole.KITCHEN);

        invite(token, "COOK@example.com", "[\"KITCHEN\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invitingTheSameEmailAgainRevokesTheOlderInvitation() throws Exception {
        String token = memberToken("owner@example.com", HotelRole.OWNER);

        invite(token, "new@example.com", "[\"WAITER\"]").andExpect(status().isCreated());
        invite(token, "new@example.com", "[\"CASHIER\"]").andExpect(status().isCreated());

        List<StaffInvitation> all = invitations.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(i -> i.getStatus() == InvitationStatus.PENDING).hasSize(1);
        assertThat(all).filteredOn(i -> i.getStatus() == InvitationStatus.REVOKED).hasSize(1);

        mvc.perform(get("/api/v1/hotels/" + hotelId + "/invitations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].roles[0]").value("CASHIER"));
    }

    @Test
    void aNonMemberGetsNotFoundAndAnonymousGetsUnauthorized() throws Exception {
        User outsider = new User("out@example.com", encoder.encode(PASSWORD), "Out");
        outsider.setEmailVerified(true);
        users.save(outsider);
        String token = login("out@example.com");

        invite(token, "x@example.com", "[\"WAITER\"]").andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/hotels/" + hotelId + "/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"roles\":[\"WAITER\"]}"))
                .andExpect(status().isUnauthorized());
    }
}