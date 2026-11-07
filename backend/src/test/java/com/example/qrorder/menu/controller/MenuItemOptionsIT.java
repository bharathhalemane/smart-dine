package com.example.qrorder.menu.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.AddOnGroup;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.model.MenuVariant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class MenuItemOptionsIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired MenuItemRepository items;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String pizza; // base price 28000

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
        pizza = items.save(new MenuItem(hotelId, "cat-1", "Pizza", null, 28000L, Diet.VEG, 0, Instant.now())).getId();
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

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String itemUrl(String itemId, String suffix) {
        return "/api/v1/hotels/" + hotelId + "/menu/items/" + itemId + (suffix == null ? "" : "/" + suffix);
    }

    private ResultActions putVariants(String bearer, String itemId, String json) throws Exception {
        return call(put(itemUrl(itemId, "variants")), bearer, json);
    }

    private ResultActions putGroups(String bearer, String itemId, String json) throws Exception {
        return call(put(itemUrl(itemId, "add-on-groups")), bearer, json);
    }

    private String body(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static final String HALF_FULL =
            "{\"variants\":[{\"name\":\"Half\",\"priceDeltaMinor\":-10000},{\"name\":\"Full\",\"priceDeltaMinor\":0}]}";

    @Test
    void aManagerSetsVariantsAndTheyAreReadBackWithGeneratedIds() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        putVariants(token, pizza, "{\"variants\":[{\"name\":\"  Half \",\"priceDeltaMinor\":-10000},{\"name\":\"Full\",\"priceDeltaMinor\":0}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].name").value("Half"))
                .andExpect(jsonPath("$.variants[0].priceDeltaMinor").value(-10000))
                .andExpect(jsonPath("$.variants[0].id").isNotEmpty())
                .andExpect(jsonPath("$.priceMinor").value(28000));

        call(get(itemUrl(pizza, null)), token, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.variants.length()").value(2));
        assertThat(items.findByIdAndHotelId(pizza, hotelId).orElseThrow().getVariants())
                .extracting(MenuVariant::name).containsExactly("Half", "Full");
    }

    @Test
    void replacingKeepsKnownIdsDropsOmittedOnesAndABadListChangesNothing() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        String first = body(putVariants(token, pizza, HALF_FULL));
        String halfId = JsonPath.read(first, "$.variants[0].id");
        String fullId = JsonPath.read(first, "$.variants[1].id");

        String second = body(putVariants(token, pizza,
                "{\"variants\":[{\"id\":\"%s\",\"name\":\"Half portion\",\"priceDeltaMinor\":-10000},{\"name\":\"Family\",\"priceDeltaMinor\":20000}]}"
                        .formatted(halfId)));
        assertThat((String) JsonPath.read(second, "$.variants[0].id")).isEqualTo(halfId); // kept, renamed
        assertThat((String) JsonPath.read(second, "$.variants[0].name")).isEqualTo("Half portion");
        assertThat((String) JsonPath.read(second, "$.variants[1].id")).isNotEqualTo(fullId); // Full was dropped

        // every one of these is rejected and must leave the stored variants exactly as they were
        List<String> bad = List.of(
                "{\"variants\":[{\"name\":\"Only one\",\"priceDeltaMinor\":0}]}",
                "{\"variants\":[{\"name\":\"Half\",\"priceDeltaMinor\":0},{\"name\":\"HALF\",\"priceDeltaMinor\":0}]}",
                "{\"variants\":[{\"name\":\"Tiny\",\"priceDeltaMinor\":-30000},{\"name\":\"Full\",\"priceDeltaMinor\":0}]}",
                "{\"variants\":[{\"id\":\"nope\",\"name\":\"Half\",\"priceDeltaMinor\":0},{\"name\":\"Full\",\"priceDeltaMinor\":0}]}",
                "{\"variants\":[{\"id\":\"%s\",\"name\":\"A\",\"priceDeltaMinor\":0},{\"id\":\"%s\",\"name\":\"B\",\"priceDeltaMinor\":0}]}"
                        .formatted(halfId, halfId),
                "{\"variants\":[{\"name\":\"\",\"priceDeltaMinor\":0},{\"name\":\"Full\",\"priceDeltaMinor\":0}]}",
                "{\"variants\":[{\"name\":\"A\"},{\"name\":\"B\",\"priceDeltaMinor\":0}]}");
        for (String json : bad) {
            putVariants(token, pizza, json)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(items.findByIdAndHotelId(pizza, hotelId).orElseThrow().getVariants())
                .extracting(MenuVariant::name).containsExactly("Half portion", "Family");
    }

    @Test
    void anEmptyListClearsTheVariants() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        putVariants(token, pizza, HALF_FULL).andExpect(status().isOk());

        putVariants(token, pizza, "{\"variants\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.variants.length()").value(0));
        assertThat(items.findByIdAndHotelId(pizza, hotelId).orElseThrow().getVariants()).isEmpty();
    }

    @Test
    void addOnGroupsAreStoredWithTheirRulesAndInvalidRulesAreRejected() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        String saved = body(putGroups(token, pizza,
                "{\"groups\":[{\"name\":\"Extras\",\"minSelect\":0,\"maxSelect\":2,\"options\":[{\"name\":\"Extra cheese\",\"priceMinor\":3000},{\"name\":\"Jalapenos\",\"priceMinor\":2000}]},"
                        + "{\"name\":\"Sauce\",\"minSelect\":1,\"maxSelect\":1,\"options\":[{\"name\":\"Red\",\"priceMinor\":0},{\"name\":\"White\",\"priceMinor\":1000}]}]}"));
        assertThat((Integer) JsonPath.read(saved, "$.addOnGroups.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(saved, "$.addOnGroups[0].id")).isNotEmpty();
        assertThat((Integer) JsonPath.read(saved, "$.addOnGroups[0].maxSelect")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(saved, "$.addOnGroups[0].options[0].priceMinor")).isEqualTo(3000);
        String groupAId = JsonPath.read(saved, "$.addOnGroups[0].id");
        String groupBOptionId = JsonPath.read(saved, "$.addOnGroups[1].options[0].id");

        List<String> bad = List.of(
                // min above max
                "{\"groups\":[{\"name\":\"G\",\"minSelect\":2,\"maxSelect\":1,\"options\":[{\"name\":\"A\",\"priceMinor\":0},{\"name\":\"B\",\"priceMinor\":0}]}]}",
                // max above the number of options
                "{\"groups\":[{\"name\":\"G\",\"minSelect\":0,\"maxSelect\":3,\"options\":[{\"name\":\"A\",\"priceMinor\":0},{\"name\":\"B\",\"priceMinor\":0}]}]}",
                // duplicate option names
                "{\"groups\":[{\"name\":\"G\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"A\",\"priceMinor\":0},{\"name\":\"a\",\"priceMinor\":0}]}]}",
                // duplicate group names
                "{\"groups\":[{\"name\":\"G\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"A\",\"priceMinor\":0}]},{\"name\":\"g\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"B\",\"priceMinor\":0}]}]}",
                // a group with no options
                "{\"groups\":[{\"name\":\"G\",\"minSelect\":0,\"maxSelect\":1,\"options\":[]}]}",
                // unknown group id
                "{\"groups\":[{\"id\":\"nope\",\"name\":\"G\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"A\",\"priceMinor\":0}]}]}",
                // an option id that belongs to a different group
                "{\"groups\":[{\"id\":\"%s\",\"name\":\"Extras\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"id\":\"%s\",\"name\":\"Extra cheese\",\"priceMinor\":3000}]}]}"
                        .formatted(groupAId, groupBOptionId),
                // negative price
                "{\"groups\":[{\"name\":\"G\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"A\",\"priceMinor\":-1}]}]}");
        for (String json : bad) {
            putGroups(token, pizza, json)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        List<AddOnGroup> stored = items.findByIdAndHotelId(pizza, hotelId).orElseThrow().getAddOnGroups();
        assertThat(stored).extracting(AddOnGroup::name).containsExactly("Extras", "Sauce");
    }

    @Test
    void onlyMenuManagersMayConfigureButAnyMemberMayRead() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        seedMember("cook@example.com", HotelRole.KITCHEN);
        seedMember("waiter@example.com", HotelRole.WAITER);
        putVariants(login("manager@example.com"), pizza, HALF_FULL).andExpect(status().isOk());

        for (String email : List.of("cook@example.com", "waiter@example.com")) {
            String token = login(email);
            putVariants(token, pizza, "{\"variants\":[]}").andExpect(status().isForbidden());
            putGroups(token, pizza, "{\"groups\":[]}").andExpect(status().isForbidden());
            call(get(itemUrl(pizza, null)), token, null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.variants.length()").value(2));
        }
        assertThat(items.findByIdAndHotelId(pizza, hotelId).orElseThrow().getVariants()).hasSize(2);
    }

    @Test
    void anItemFromAnotherHotelIsNotFoundAndStaysUntouched() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String foreign = items.save(new MenuItem(otherHotelId, "cat-9", "Lassi", null, 6000L, Diet.VEG, 0,
                Instant.now())).getId();
        String token = login("manager@example.com");

        putVariants(token, foreign, HALF_FULL).andExpect(status().isNotFound());
        putGroups(token, foreign, "{\"groups\":[]}").andExpect(status().isNotFound());

        assertThat(items.findByIdAndHotelId(foreign, otherHotelId).orElseThrow().getVariants()).isEmpty();
    }

    @Test
    void aBasePriceThatWouldMakeAVariantNegativeIsRefused() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        putVariants(token, pizza, HALF_FULL).andExpect(status().isOk()); // Half is base - 10000

        call(patch(itemUrl(pizza, null)), token, "{\"priceMinor\":5000}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(items.findByIdAndHotelId(pizza, hotelId).orElseThrow().getPriceMinor()).isEqualTo(28000L);

        call(patch(itemUrl(pizza, null)), token, "{\"priceMinor\":12000}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.priceMinor").value(12000));
    }
}