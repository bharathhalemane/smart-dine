package com.example.qrorder.menu.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
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

class MenuCategoryIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired MenuCategoryRepository categories;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        categories.deleteAll();
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

    private String base() {
        return "/api/v1/hotels/" + hotelId + "/menu/categories";
    }

    private ResultActions create(String bearer, String json) throws Exception {
        return call(post(base()), bearer, json);
    }

    private String createdId(ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void aManagerCreatesCategoriesAndTheyAreAppendedInOrder() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        create(token, "{\"name\":\"  Starters  \",\"description\":\"Small plates\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Starters"))
                .andExpect(jsonPath("$.description").value("Small plates"))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.active").value(true));
        create(token, "{\"name\":\"Mains\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sortOrder").value(1));

        MenuCategory saved = categories.findAll().stream().filter(c -> c.getName().equals("Starters")).findFirst().orElseThrow();
        assertThat(saved.getHotelId()).isEqualTo(hotelId);
        assertThat(saved.getNameKey()).isEqualTo("starters");
    }

    @Test
    void anyMemberMayReadButOnlyMenuManagersMayChange() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        seedMember(hotelId, "cook@example.com", HotelRole.KITCHEN);
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        String id = createdId(create(login("manager@example.com"), "{\"name\":\"Starters\"}"));

        for (String email : List.of("cook@example.com", "waiter@example.com")) {
            String token = login(email);
            call(get(base()), token, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
            call(get(base() + "/" + id), token, null).andExpect(status().isOk());

            create(token, "{\"name\":\"Desserts\"}").andExpect(status().isForbidden());
            call(patch(base() + "/" + id), token, "{\"name\":\"X\"}").andExpect(status().isForbidden());
            call(delete(base() + "/" + id), token, null).andExpect(status().isForbidden());
            call(put(base() + "/order"), token, "{\"categoryIds\":[\"%s\"]}".formatted(id))
                    .andExpect(status().isForbidden());
            call(get(base() + "?includeInactive=true"), token, null).andExpect(status().isForbidden());
        }
        assertThat(categories.count()).isEqualTo(1);
        assertThat(categories.findAll().get(0).getName()).isEqualTo("Starters");
    }

    @Test
    void namesAreUniquePerHotelIgnoringCaseButNotAcrossHotels() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        seedMember(otherHotelId, "other@example.com", HotelRole.MANAGER);

        create(login("manager@example.com"), "{\"name\":\"Starters\"}").andExpect(status().isCreated());
        create(login("manager@example.com"), "{\"name\":\"  STARTERS \"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        call(post("/api/v1/hotels/" + otherHotelId + "/menu/categories"), login("other@example.com"),
                "{\"name\":\"Starters\"}").andExpect(status().isCreated());
        assertThat(categories.count()).isEqualTo(2);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        create(token, "{\"name\":\"\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(token, "{\"name\":\"%s\"}".formatted("x".repeat(61))).andExpect(status().isBadRequest());
        create(token, "{\"name\":\"Ok\",\"description\":\"%s\"}".formatted("d".repeat(301))).andExpect(status().isBadRequest());
        create(token, "{\"name\":\"Ok\",\"sortOrder\":-1}").andExpect(status().isBadRequest());
        create(token, "{}").andExpect(status().isBadRequest());
        assertThat(categories.count()).isZero();
    }

    @Test
    void updatingChangesOnlyTheFieldsSentAndRejectsATakenName() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        String starters = createdId(create(token, "{\"name\":\"Starters\",\"description\":\"Small plates\"}"));
        createdId(create(token, "{\"name\":\"Mains\"}"));

        call(patch(base() + "/" + starters), token, "{\"description\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Starters"))
                .andExpect(jsonPath("$.description").doesNotExist());

        call(patch(base() + "/" + starters), token, "{\"name\":\"starters\"}") // case-only change is fine
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("starters"));
        call(patch(base() + "/" + starters), token, "{\"name\":\"MAINS\"}").andExpect(status().isConflict());
        call(patch(base() + "/" + starters), token, "{\"name\":\"   \"}").andExpect(status().isBadRequest());

        assertThat(categories.findByIdAndHotelId(starters, hotelId).orElseThrow().getName()).isEqualTo("starters");
    }

    @Test
    void deleteDeactivatesAndInactiveCategoriesAreHiddenUnlessAManagerAsks() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        String manager = login("manager@example.com");
        String waiter = login("waiter@example.com");
        String starters = createdId(create(manager, "{\"name\":\"Starters\"}"));
        createdId(create(manager, "{\"name\":\"Mains\"}"));

        call(delete(base() + "/" + starters), manager, null).andExpect(status().isNoContent());

        assertThat(categories.findByIdAndHotelId(starters, hotelId).orElseThrow().isActive()).isFalse();
        call(get(base()), waiter, null)
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Mains"));
        call(get(base() + "?includeInactive=true"), manager, null).andExpect(jsonPath("$.length()").value(2));

        call(patch(base() + "/" + starters), manager, "{\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        call(get(base()), waiter, null).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void reorderingFollowsTheRequestedOrderAndBadRequestsChangeNothing() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        String a = createdId(create(token, "{\"name\":\"Starters\"}"));
        String b = createdId(create(token, "{\"name\":\"Mains\"}"));
        String c = createdId(create(token, "{\"name\":\"Desserts\"}"));
        String foreign = categories.save(new MenuCategory(otherHotelId, "Drinks", null, 0, Instant.now())).getId();

        call(put(base() + "/order"), token, "{\"categoryIds\":[\"%s\",\"%s\",\"%s\"]}".formatted(c, a, b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Desserts"))
                .andExpect(jsonPath("$[1].name").value("Starters"))
                .andExpect(jsonPath("$[2].name").value("Mains"))
                .andExpect(jsonPath("$[2].sortOrder").value(2));
        call(get(base()), token, null)
                .andExpect(jsonPath("$[0].name").value("Desserts"))
                .andExpect(jsonPath("$[1].name").value("Starters"))
                .andExpect(jsonPath("$[2].name").value("Mains"));

        // duplicate id, missing id, and another hotel's id are all rejected without changing anything
        call(put(base() + "/order"), token, "{\"categoryIds\":[\"%s\",\"%s\",\"%s\"]}".formatted(a, a, b))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        call(put(base() + "/order"), token, "{\"categoryIds\":[\"%s\",\"%s\"]}".formatted(a, b))
                .andExpect(status().isBadRequest());
        call(put(base() + "/order"), token, "{\"categoryIds\":[\"%s\",\"%s\",\"%s\"]}".formatted(a, b, foreign))
                .andExpect(status().isBadRequest());

        call(get(base()), token, null)
                .andExpect(jsonPath("$[0].name").value("Desserts"))
                .andExpect(jsonPath("$[1].name").value("Starters"))
                .andExpect(jsonPath("$[2].name").value("Mains"));
    }

    @Test
    void aCategoryFromAnotherHotelIsNotFoundAndStaysUntouched() throws Exception {
        seedMember(hotelId, "manager@example.com", HotelRole.MANAGER);
        String foreign = categories.save(new MenuCategory(otherHotelId, "Drinks", null, 0, Instant.now())).getId();
        String token = login("manager@example.com");

        call(get(base() + "/" + foreign), token, null).andExpect(status().isNotFound());
        call(patch(base() + "/" + foreign), token, "{\"name\":\"Hacked\"}").andExpect(status().isNotFound());
        call(delete(base() + "/" + foreign), token, null).andExpect(status().isNotFound());

        MenuCategory untouched = categories.findByIdAndHotelId(foreign, otherHotelId).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("Drinks");
        assertThat(untouched.isActive()).isTrue();
    }
}