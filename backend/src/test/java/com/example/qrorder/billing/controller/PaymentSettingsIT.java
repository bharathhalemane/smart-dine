package com.example.qrorder.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.billing.repository.PaymentSettingsRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PaymentSettingsIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String CASH_AND_UPI = "{\"enabledMethods\":[\"CASH\",\"UPI\"],\"upiId\":\"shop.name-1@okbank\","
            + "\"payeeName\":\"Spice Garden\",\"partialPaymentsAllowed\":true}";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired PaymentSettingsRepository settings;
    @Autowired PasswordEncoder encoder;

    String url;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        settings.deleteAll();
        String hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        String otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "owner@example.com", "Owner Asha", HotelRole.OWNER);
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(otherHotelId, "otherowner@example.com", "Other Owner", HotelRole.OWNER);
        url = "/api/v1/hotels/" + hotelId + "/payments/settings";
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
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

    private ResultActions call(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private ResultActions save(String json) throws Exception {
        return call(put(url), "owner@example.com", json);
    }

    // ---------- tests ----------

    @Test
    void aHotelThatNeverConfiguredAnythingAcceptsCashOnly() throws Exception {
        call(get(url), "cashier@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledMethods.length()").value(1))
                .andExpect(jsonPath("$.enabledMethods[0]").value("CASH"))
                .andExpect(jsonPath("$.upiId").doesNotExist())
                .andExpect(jsonPath("$.partialPaymentsAllowed").value(true));
        assertThat(settings.count()).isZero(); // reading did not create anything
    }

    @Test
    void onlyTheOwnerChangesThemAndStaffWhoTakePaymentCanRead() throws Exception {
        for (String reader : List.of("owner@example.com", "manager@example.com", "cashier@example.com")) {
            call(get(url), reader, null).andExpect(status().isOk());
        }
        call(get(url), "w1@example.com", null).andExpect(status().isForbidden());
        call(get(url), "cook@example.com", null).andExpect(status().isForbidden());
        call(get(url), null, null).andExpect(status().isUnauthorized());
        call(get(url), "otherowner@example.com", null).andExpect(status().isNotFound()); // not a member here

        for (String notOwner : List.of("manager@example.com", "cashier@example.com", "w1@example.com")) {
            call(put(url), notOwner, CASH_AND_UPI).andExpect(status().isForbidden());
        }
        call(put(url), null, CASH_AND_UPI).andExpect(status().isUnauthorized());
        call(put(url), "otherowner@example.com", CASH_AND_UPI).andExpect(status().isNotFound());
        assertThat(settings.count()).isZero();

        save(CASH_AND_UPI)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledMethods[0]").value("CASH"))
                .andExpect(jsonPath("$.enabledMethods[1]").value("UPI"))
                .andExpect(jsonPath("$.upiId").value("shop.name-1@okbank"))
                .andExpect(jsonPath("$.payeeName").value("Spice Garden"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        call(get(url), "cashier@example.com", null).andExpect(jsonPath("$.upiId").value("shop.name-1@okbank"));
    }

    @Test
    void acceptingUpiNeedsAValidIdAndAName() throws Exception {
        save("{\"enabledMethods\":[\"UPI\"],\"partialPaymentsAllowed\":true}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        save("{\"enabledMethods\":[\"UPI\"],\"upiId\":\"shop@okbank\",\"partialPaymentsAllowed\":true}")
                .andExpect(status().isBadRequest());
        save("{\"enabledMethods\":[\"UPI\"],\"payeeName\":\"Spice Garden\",\"partialPaymentsAllowed\":true}")
                .andExpect(status().isBadRequest());

        for (String badId : List.of("no-at-sign", "a@b", "has space@okbank", "shop@ok-bank", "@okbank", "shop@",
                "x".repeat(65) + "@okbank", "x".repeat(101))) {
            save("{\"enabledMethods\":[\"UPI\"],\"upiId\":\"%s\",\"payeeName\":\"Spice Garden\",\"partialPaymentsAllowed\":true}"
                    .formatted(badId)).andExpect(status().isBadRequest());
        }
        save("{\"enabledMethods\":[\"UPI\"],\"upiId\":\"shop@okbank\",\"payeeName\":\"%s\",\"partialPaymentsAllowed\":true}"
                .formatted("n".repeat(61))).andExpect(status().isBadRequest());
        assertThat(settings.count()).isZero();

        // a badly formed id is refused even when UPI is not switched on
        save("{\"enabledMethods\":[\"CASH\"],\"upiId\":\"nonsense\",\"partialPaymentsAllowed\":true}")
                .andExpect(status().isBadRequest());
        // but cash only needs nothing else
        save("{\"enabledMethods\":[\"CASH\"],\"partialPaymentsAllowed\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.upiId").doesNotExist());
    }

    @Test
    void methodsMustBeKnownAndThereMustBeAtLeastOne() throws Exception {
        for (String bad : List.of(
                "{\"enabledMethods\":[],\"partialPaymentsAllowed\":true}",
                "{\"partialPaymentsAllowed\":true}",
                "{\"enabledMethods\":[\"BITCOIN\"],\"partialPaymentsAllowed\":true}",
                "{\"enabledMethods\":[\"ONLINE\"],\"partialPaymentsAllowed\":true}",
                "{\"enabledMethods\":[\"CASH\"]}")) {
            save(bad).andExpect(status().isBadRequest());
        }
        assertThat(settings.count()).isZero();

        save("{\"enabledMethods\":[\"CARD\",\"CASH\",\"CASH\"],\"partialPaymentsAllowed\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledMethods.length()").value(2))
                .andExpect(jsonPath("$.enabledMethods[0]").value("CASH"))
                .andExpect(jsonPath("$.enabledMethods[1]").value("CARD"))
                .andExpect(jsonPath("$.partialPaymentsAllowed").value(false));
    }

    @Test
    void savingAgainReplacesTheSettingsRatherThanAddingAnother() throws Exception {
        save(CASH_AND_UPI).andExpect(status().isOk());
        save("{\"enabledMethods\":[\"CASH\",\"CARD\"],\"partialPaymentsAllowed\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upiId").doesNotExist())
                .andExpect(jsonPath("$.payeeName").doesNotExist());

        assertThat(settings.count()).isEqualTo(1);
        call(get(url), "manager@example.com", null)
                .andExpect(jsonPath("$.enabledMethods[1]").value("CARD"))
                .andExpect(jsonPath("$.partialPaymentsAllowed").value(false));
    }
}